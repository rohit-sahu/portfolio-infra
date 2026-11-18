#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash scripts/destroy-test.sh --profile PROFILE --account ACCOUNT_ID \
         --env test-NAME [--region REGION] [--sso]

Permanently deletes the named test deployment, including its retained uploads,
log groups, and any legacy ECR repositories found in its stack inventory.
The region defaults to ap-south-1. Requires AWS CLI v2 and jq.
Does not delete CDKToolkit, GHCR packages, or resources outside these stacks.
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

profile=''
account=''
region='ap-south-1'
deploy_env=''
sso=false

while (( $# )); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --sso) sso=true; shift ;;
    --profile|--account|--region|--env)
      [[ -n "${2:-}" && "$2" != --* ]] || die "$1 requires a value"
      case "$1" in
        --profile) profile="$2" ;;
        --account) account="$2" ;;
        --region) region="$2" ;;
        --env) deploy_env="$2" ;;
      esac
      shift 2
      ;;
    *) die "Unknown option: $1 (use --help)" ;;
  esac
done

[[ "$profile" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.@+-]*$ ]] || die 'Provide a valid --profile'
[[ "$account" =~ ^[0-9]{12}$ ]] || die '--account must be a 12-digit AWS account ID'
[[ "$region" =~ ^[a-z]{2}(-[a-z0-9]+)+-[0-9]+$ ]] || die 'Invalid AWS region'
[[ "$deploy_env" =~ ^test-[a-z0-9]([a-z0-9-]*[a-z0-9])?$ && ${#deploy_env} -le 40 ]] \
  || die '--env must be the exact test-* prefix used for deployment'

for tool in aws jq; do
  command -v "$tool" >/dev/null || die "Required tool not found: $tool"
done

unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN
export AWS_PAGER=''
aws_target() {
  aws --profile "$profile" --region "$region" --output json "$@"
}

if "$sso"; then
  aws_target sso login
fi
actual_account="$(aws_target sts get-caller-identity | jq -er .Account)"
[[ "$actual_account" == "$account" ]] \
  || die "Profile $profile resolved to account $actual_account, not $account"

stack_suffixes=(svc-orders svc-worker lambda-test platform messaging network)
stack_summaries="$(aws_target cloudformation list-stacks)"
inventory="$(mktemp)"
batch_file=''
trap 'rm -f -- "$inventory"; if [[ -n "$batch_file" ]]; then rm -f -- "$batch_file"; fi' EXIT
batch_file="$(mktemp)"

find_stack() {
  jq -c --arg name "$1" '
    [.StackSummaries[] | select(.StackName == $name)]
    | sort_by([(.StackStatus != "DELETE_COMPLETE"), .CreationTime]) | last // empty
  ' <<< "$2"
}

for suffix in "${stack_suffixes[@]}"; do
  stack_name="$deploy_env-$suffix"
  stack="$(find_stack "$stack_name" "$stack_summaries")"
  [[ -n "$stack" ]] || continue
  stack_id="$(jq -er .StackId <<< "$stack")"
  stack_status="$(jq -er .StackStatus <<< "$stack")"
  case "$stack_status" in
    DELETE_IN_PROGRESS|DELETE_COMPLETE) ;;
    *_IN_PROGRESS|*_CLEANUP_IN_PROGRESS) die "Wait for $stack_name ($stack_status) before teardown" ;;
  esac
  aws_target cloudformation list-stack-resources --stack-name "$stack_id" |
    jq -r '
      .StackResourceSummaries[]
      | select(.PhysicalResourceId != null and .PhysicalResourceId != "")
      | if .ResourceType == "AWS::Logs::LogGroup" then ["logs", .PhysicalResourceId]
        elif .ResourceType == "AWS::Lambda::Function" then ["logs", "/aws/lambda/" + .PhysicalResourceId]
        elif .ResourceType == "AWS::S3::Bucket" and .ResourceStatus != "DELETE_COMPLETE" then ["bucket", .PhysicalResourceId]
        elif .ResourceType == "AWS::ECR::Repository" and .ResourceStatus != "DELETE_COMPLETE" then ["ecr", .PhysicalResourceId]
        else empty end | @tsv
    ' >> "$inventory"
  printf 'Stack: %s (%s)\n' "$stack_name" "$stack_status"
done

printf 'Target: account=%s region=%s profile=%s environment=%s\n' "$account" "$region" "$profile" "$deploy_env"
if [[ -s "$inventory" ]]; then
  printf 'Additional resources checked after stack deletion (if still present):\n'
  sort -u "$inventory"
fi
printf 'Type %s to permanently delete these test resources: ' "$deploy_env"
read -r confirmation || die 'Interactive confirmation required'
[[ "$confirmation" == "$deploy_env" ]] || die 'Teardown cancelled'

for suffix in "${stack_suffixes[@]}"; do
  stack_name="$deploy_env-$suffix"
  stack="$(find_stack "$stack_name" "$stack_summaries")"
  [[ -n "$stack" ]] || continue
  stack_id="$(jq -er .StackId <<< "$stack")"
  case "$(jq -er .StackStatus <<< "$stack")" in
    DELETE_COMPLETE) continue ;;
    DELETE_IN_PROGRESS) ;;
    *)
      printf 'Deleting %s\n' "$stack_name"
      aws_target cloudformation delete-stack --stack-name "$stack_id"
      ;;
  esac
  aws_target cloudformation wait stack-delete-complete --stack-name "$stack_id" \
    || die "Deletion did not complete for $stack_name; inspect its CloudFormation events and rerun"
done

delete_bucket() {
  local bucket="$1" owned result
  # Verify ownership through the authenticated account's bucket inventory.
  # This also works with CLI versions lacking --expected-bucket-owner.
  owned="$(aws_target s3api list-buckets |
    jq --arg bucket "$bucket" 'any(.Buckets[]?; .Name == $bucket)')"
  [[ "$owned" == true ]] || return 0

  printf 'Emptying versioned bucket %s\n' "$bucket"
  while true; do
    aws_target s3api list-object-versions --bucket "$bucket" \
      --no-paginate --max-keys 1000 |
      jq -c '{Objects: ([.Versions[]?, .DeleteMarkers[]?] | map({Key, VersionId})), Quiet: true}' > "$batch_file"
    [[ "$(jq '.Objects | length' "$batch_file")" != 0 ]] || break
    result="$(aws_target s3api delete-objects --bucket "$bucket" \
      --delete "file://$batch_file")"
    if ! jq -e '(.Errors // []) | length == 0' >/dev/null <<< "$result"; then
      printf '%s\n' "$result" >&2
      die "Could not delete every object version from $bucket"
    fi
  done
  aws_target s3api delete-bucket --bucket "$bucket"
}

while IFS=$'\t' read -r kind name; do
  case "$kind" in
    bucket) delete_bucket "$name" ;;
    logs)
      exists="$(aws_target logs describe-log-groups --log-group-name-prefix "$name" |
        jq --arg name "$name" 'any(.logGroups[]?; .logGroupName == $name)')"
      if [[ "$exists" == true ]]; then
        aws_target logs delete-log-group --log-group-name "$name"
      fi
      ;;
    ecr)
      [[ "$name" == "$deploy_env/orders" || "$name" == "$deploy_env/worker" ]] \
        || die "Unexpected legacy ECR repository $name; review it manually"
      exists="$(aws_target ecr describe-repositories --registry-id "$account" |
        jq --arg name "$name" 'any(.repositories[]?; .repositoryName == $name)')"
      if [[ "$exists" == true ]]; then
        aws_target ecr delete-repository --registry-id "$account" --repository-name "$name" --force >/dev/null
      fi
      ;;
  esac
done <<< "$(sort -u "$inventory")"

remaining="$(aws_target cloudformation list-stacks |
  jq -r --arg prefix "$deploy_env-" '
    .StackSummaries[]
    | select((.StackName | startswith($prefix)) and .StackStatus != "DELETE_COMPLETE")
    | "\(.StackName) (\(.StackStatus))"
  ')"
[[ -z "$remaining" ]] || die "Stacks still present; review manually: $remaining"
printf 'Teardown complete for %s. CDKToolkit and GHCR were left untouched.\n' "$deploy_env"
