#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash scripts/deploy-test.sh --profile PROFILE --account ACCOUNT_ID \
         (--image-tag TAG | --push-images --github-user LOGIN) [options]

  --profile PROFILE       Configured AWS CLI profile (required)
  --account ACCOUNT_ID    Expected 12-digit AWS account ID (required)
  --region REGION         Target AWS region (default: ap-south-1)
  --env NAME              Disposable test-* stack prefix (default: UTC timestamp)
  --image-prefix PREFIX   Lowercase GHCR repo path (default: ghcr.io/rohit-sahu/portfolio-infra)
  --image-tag TAG         Reuse this published tag, or use it when pushing
  --services LIST         Comma-separated ECS services: orders,worker (default: all stacks)
  --push-images           Build and push selected images (default tag: manual-<UTC timestamp>)
  --github-user LOGIN     GHCR login for --push-images; Docker prompts for a classic PAT
  --sso                   Run aws sso login before checking the AWS account
  --skip-bootstrap        Use an existing compatible CDKToolkit stack
  --help                  Show this help

With --services, CDK deploys only the selected ECS services and their stack
dependencies, not the standalone Lambda test stack. Images must be public
and support linux/amd64. Deployment requires interactive
confirmation and creates billable AWS resources. This script does not destroy them.
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

timestamp="$(date -u +%Y%m%d%H%M%S)"
profile=''
account=''
region='ap-south-1'
deploy_env="test-$timestamp"
image_prefix='ghcr.io/rohit-sahu/portfolio-infra'
image_tag=''
github_user=''
service_list=''
push_images=false
sso=false
bootstrap=true

while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --push-images) push_images=true; shift ;;
    --sso) sso=true; shift ;;
    --skip-bootstrap) bootstrap=false; shift ;;
    --profile|--account|--region|--env|--image-prefix|--image-tag|--github-user|--services)
      [[ -n "${2:-}" && "$2" != --* ]] || die "$1 requires a value"
      case "$1" in
        --profile) profile="$2" ;;
        --account) account="$2" ;;
        --region) region="$2" ;;
        --env) deploy_env="$2" ;;
        --image-prefix) image_prefix="$2" ;;
        --image-tag) image_tag="$2" ;;
        --github-user) github_user="$2" ;;
        --services)
          [[ -z "$service_list" ]] || die 'Pass --services only once, with a comma-separated list'
          service_list="$2"
          ;;
      esac
      shift 2
      ;;
    *) die "Unknown option: $1 (use --help)" ;;
  esac
done

[[ -n "$profile" ]] || die '--profile is required'
[[ "$account" =~ ^[0-9]{12}$ ]] || die '--account must be a 12-digit AWS account ID'
[[ "$region" =~ ^[a-z]{2}(-[a-z0-9]+)+-[0-9]+$ ]] || die 'Invalid AWS region'
[[ "$deploy_env" =~ ^test-[a-z0-9]([a-z0-9-]*[a-z0-9])?$ && ${#deploy_env} -le 40 ]] \
  || die '--env must start with test-, end in a letter or digit, and be at most 40 characters'
[[ "$image_prefix" =~ ^ghcr\.io/[a-z0-9]+([._-][a-z0-9]+)*/[a-z0-9]+([._-][a-z0-9]+)*$ ]] \
  || die '--image-prefix must be a lowercase ghcr.io/owner/repository path'
if "$push_images"; then
  [[ -n "$github_user" ]] || die '--push-images requires --github-user'
  image_tag="${image_tag:-manual-$timestamp}"
else
  [[ -z "$github_user" ]] || die '--github-user is only used with --push-images'
fi
[[ "$image_tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] \
  || die 'Provide a valid --image-tag or use --push-images'

deploy_targets=(--all)
services=(orders worker)
if [[ -n "$service_list" ]]; then
  [[ "$service_list" =~ ^[a-z]+(,[a-z]+)*$ ]] \
    || die '--services must be a comma-separated list, e.g. orders,worker'
  IFS=, read -r -a services <<< "$service_list"
  deploy_targets=()
  seen=','
  for service in "${services[@]}"; do
    case "$service" in
      orders|worker) ;;
      *) die "Unknown service '$service'; supported services: orders, worker" ;;
    esac
    [[ "$seen" != *",$service,"* ]] || die "Duplicate service '$service'"
    seen+="$service,"
    deploy_targets+=("$deploy_env-svc-$service")
  done
fi

for tool in aws npx node java mvn docker; do
  command -v "$tool" >/dev/null || die "Required tool not found: $tool"
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
cdk=(npx --yes --package aws-cdk@2.1148.1 cdk)

# Use the selected profile, not any static credentials left in the shell.
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN
export AWS_PROFILE="$profile" AWS_DEFAULT_PROFILE="$profile"
export AWS_REGION="$region" AWS_DEFAULT_REGION="$region" AWS_PAGER=''

if "$sso"; then
  aws sso login --profile "$profile"
fi
actual_account="$(aws sts get-caller-identity --query Account --output text)" \
  || die "Could not authenticate with profile $profile; sign in or use --sso"
[[ "$actual_account" == "$account" ]] \
  || die "Profile $profile resolved to account $actual_account, not $account"

printf 'AWS account: %s\nRegion: %s\nTest environment: %s\n' "$account" "$region" "$deploy_env"
for service in "${services[@]}"; do
  printf 'Image: %s/%s:%s\n' "$image_prefix" "$service" "$image_tag"
done
printf 'CDK targets: %s\n' "${deploy_targets[*]}"
printf 'Bootstrap: %s | Build and push images: %s\n' "$bootstrap" "$push_images"
printf 'This deploys billable resources. Type %s to continue: ' "$deploy_env"
read -r confirmation || die 'Interactive confirmation required'
[[ "$confirmation" == "$deploy_env" ]] || die 'Deployment cancelled'

if "$push_images"; then
  docker info >/dev/null || die 'Start Docker Desktop or your Docker daemon'
  docker buildx version >/dev/null || die 'Docker Buildx is required'
  docker login ghcr.io --username "$github_user"
  for service in "${services[@]}"; do
    image="$image_prefix/$service:$image_tag"
    docker buildx build --platform linux/amd64 --load --tag "$image" \
      --label "org.opencontainers.image.source=https://github.com/${image_prefix#ghcr.io/}" \
      "services/$service"
    docker push "$image"
  done
fi

# Test ECS's anonymous access without modifying the user's Docker login.
anonymous_dir="$(mktemp -d)"
trap 'rmdir "$anonymous_dir"' EXIT
for service in "${services[@]}"; do
  image="$image_prefix/$service:$image_tag"
  docker --config "$anonymous_dir" manifest inspect "$image" >/dev/null \
    || die "Cannot read $image anonymously. Publish the tag and make its package Public, then rerun with the same service selection, --image-tag $image_tag, and without --push-images or --github-user."
done

mvn --batch-mode -f infra/pom.xml test
cd infra
if "$bootstrap"; then
  "${cdk[@]}" bootstrap "aws://$account/$region"
fi
printf 'Keep this target for teardown, even if deploy fails: profile=%s region=%s env=%s\n' \
  "$profile" "$region" "$deploy_env"
"${cdk[@]}" deploy "${deploy_targets[@]}" \
  -c "env=$deploy_env" -c "imagePrefix=$image_prefix" -c "imageTag=$image_tag"
printf 'See README.md#destroy-an-aws-test-deployment for cleanup of %s.\n' "$deploy_env"
