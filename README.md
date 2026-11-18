# Portfolio infrastructure

This repository contains an AWS CDK Java app in `infra/` that provisions a VPC, an ECS-on-EC2 platform, an application load balancer, and S3/SNS/SQS messaging resources. ECS pulls the `orders` and `worker` images from public GitHub Container Registry (GHCR) packages. Both services are placeholders; order processing and queue consumption are not implemented.

## Validate and synthesize

Requirements: Java 21, Maven, Node.js, and the AWS CDK CLI.

```sh
cd infra
mvn test
cdk synth -c env=dev
```

Validation and synthesis do not require AWS credentials or bootstrap. For
deployment, follow the [manual AWS instructions](#deploy-and-run-manually-in-aws)
or [configure GitHub Actions](#configure-github-actions-and-aws).

The platform currently exposes the orders placeholder over HTTP on port 80. Configure HTTPS and an ACM certificate before using it for production traffic.

## Run manually on your computer

These commands use Bash or Zsh. Start from the repository root, not `infra/`.
Install Java 21 and Maven, Node.js 22 for the CDK tests, and Python 3.13 for
the Lambda handler tests. These local steps don't deploy anything or incur AWS
charges. If you're deploying to AWS for an end-to-end test, see
[Deploy and run manually in AWS](#deploy-and-run-manually-in-aws) and
[Destroy an AWS test deployment](#destroy-an-aws-test-deployment).

1. **Build the services and run the tests:**

   ```sh
   mvn --batch-mode test
   mvn --batch-mode -f infra/pom.xml test
   mvn --batch-mode -f services/orders/pom.xml package
   mvn --batch-mode -f services/worker/pom.xml package
   python3 -B -m unittest discover -s services/lambda-test
   ```

2. **Start orders in one terminal:**

   ```sh
   java -jar services/orders/target/orders-service-1.0.0.jar
   ```

   In another terminal, call its health endpoint:

   ```sh
   curl --fail http://localhost:8080/orders/health
   ```

   Expect `ok` and HTTP 200. Other `/orders` operations return HTTP 501 because
   this is a placeholder. If port 8080 is occupied, start it with
   `PORT=18080 java -jar services/orders/target/orders-service-1.0.0.jar`
   and call port 18080 instead.

3. **Start the worker in a separate terminal:**

   ```sh
   java -jar services/worker/target/worker-service-1.0.0.jar
   ```

   It prints a startup message and waits. It has no HTTP endpoint and does not
   consume SQS messages yet. Stop either Java process with **Ctrl+C**.

For an optional local container run, start Docker Desktop (or your Docker
daemon), then run these commands from the repository root:

```sh
docker build --tag portfolio-orders:local services/orders
docker build --tag portfolio-worker:local services/worker
docker run --rm --name portfolio-orders-local --publish 127.0.0.1:8080:8080 portfolio-orders:local
```

In another terminal, run `curl --fail http://localhost:8080/orders/health`.
In its own terminal, run `docker run --rm --name portfolio-worker-local portfolio-worker:local`.
Do not run the Java orders process and its container on the same host port.

**Local cleanup:** Press **Ctrl+C** in each terminal that is running a JAR or
container. If a container is still running, stop it by name with
`docker stop portfolio-orders-local` or `docker stop portfolio-worker-local`;
`--rm` removes it when stopped. To reclaim local disk space, remove just the
test images with `docker image rm portfolio-orders:local portfolio-worker:local`.
No `cdk destroy` is needed for local-only testing.

The CDK code is a provisioning application, not an HTTP server. The Lambda
handler runs in AWS after deployment; its unit tests exercise the handler
locally without emulating the AWS event delivery services.

## Deploy and run manually in AWS

Run `cdk bootstrap` and `cdk deploy` from your local terminal; the stacks
and services run in your AWS account, not on your laptop. GitHub Actions
is not required. This creates billable AWS resources. For a disposable
end-to-end test, prefer a unique environment
prefix such as `test-20261118` so cleanup cannot affect CI's `dev` or `prod`
stacks. Set `DEPLOY_ENV` to `dev` only if you intend to use that environment.

### One-command test deployment

From the repository root, run the script with your configured AWS profile,
expected account ID, and a tag already published to both public GHCR packages:

Run `aws configure list-profiles` and replace `YOUR_AWS_PROFILE` with a
configured profile. Verify the target account with
`aws sts get-caller-identity --profile YOUR_AWS_PROFILE` after signing in.

```sh
bash scripts/deploy-test.sh --profile YOUR_AWS_PROFILE --account 123456789012 \
  --image-tag YOUR_PUBLISHED_TAG --sso
```

Omit `--sso` if the profile does not use SSO. Add `--skip-bootstrap` if the
target already has a compatible `CDKToolkit` stack. The script uses the pinned
CDK CLI through `npx`, checks the AWS account and public images, runs the
infrastructure tests, and deploys all six stacks after confirmation.

To publish local source instead of reusing images:

```sh
bash scripts/deploy-test.sh --profile YOUR_AWS_PROFILE --account 123456789012 \
  --push-images --github-user YOUR_GITHUB_LOGIN --sso
```

Use `--services orders` to build, publish, and deploy only orders, or pass
`--services orders,worker` to select both ECS services:

```sh
bash scripts/deploy-test.sh --profile YOUR_AWS_PROFILE --account 123456789012 \
  --services orders,worker --push-images --github-user YOUR_GITHUB_LOGIN --sso
```

The selection also works with `--image-tag` to reuse published images. CDK
includes the selected services' shared infrastructure dependencies but skips
the standalone Lambda test stack. Omit `--services` for all six stacks.
Selecting fewer services does not delete previously deployed stacks.

Start Docker first and enter a GitHub classic PAT with `write:packages` when
Docker prompts. Newly published GHCR packages are private by default. If the
script stops at the anonymous image check, set both packages to **Public**,
then rerun with the same service selection and the printed `--image-tag`
instead of `--push-images` and `--github-user`.
Run `bash scripts/deploy-test.sh --help` for all options. Save the printed
profile, region, and test environment for [teardown](#destroy-an-aws-test-deployment).

### Step-by-step deployment and smoke tests

1. **Install tools and authenticate.** You need Java 21, Maven, Node.js 22,
   AWS CLI v2, and a running Docker daemon with Buildx support. Install the same
   CDK CLI version used by CI:

   ```sh
   npm install --global aws-cdk@2.1148.1
   ```

   Configure an AWS CLI profile using your organization's login method. For
   IAM Identity Center (SSO), for example:

   ```sh
   aws configure sso --profile portfolio-dev
   aws sso login --profile portfolio-dev
   export AWS_PROFILE=portfolio-dev
   export AWS_REGION=ap-south-1
   export AWS_DEFAULT_REGION="$AWS_REGION"
   aws sts get-caller-identity
   ```

   Verify that the displayed account is the intended target. Local credentials
   need CDK deployment permissions; initial bootstrapping needs
   administrator-approved provisioning permissions. GitHub environment secrets
   and the GitHub OIDC provider are not needed for local deployment.

2. **Set deployment variables.** Run from the repository root and keep this
   shell open through the remaining steps:

   ```sh
   export DEPLOY_ENV="test-$(date -u +%Y%m%d%H%M%S)"
   export IMAGE_PREFIX=ghcr.io/rohit-sahu/portfolio-infra
   export IMAGE_TAG="$(git rev-parse HEAD)"
   export GITHUB_USER=your-github-login
   ```

   Commit the source you intend to deploy before choosing a commit-based tag.
   If testing uncommitted changes, use a unique tag instead, for example
   `export IMAGE_TAG="manual-$(date -u +%Y%m%d%H%M%S)"`, to avoid overwriting a
   CI image tagged with an existing commit SHA.

3. **Bootstrap, then preview.** Run this once per AWS account and region with
   approved credentials. Skip it if that target already has a compatible
   `CDKToolkit` stack. Even with GHCR, CDK still uploads the Lambda asset
   used to restrict the VPC's default security group.

   ```sh
   ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" &&
     cdk bootstrap "aws://$ACCOUNT_ID/$AWS_REGION"
   ```

   ```sh
   mvn --batch-mode -f infra/pom.xml test
   (
     cd infra
     cdk synth '*' -c "env=$DEPLOY_ENV" -c "imagePrefix=$IMAGE_PREFIX" -c "imageTag=$IMAGE_TAG" --quiet
     cdk diff '*' -c "env=$DEPLOY_ENV" -c "imagePrefix=$IMAGE_PREFIX" -c "imageTag=$IMAGE_TAG"
   )
   ```

   Review the proposed resources and IAM permissions before continuing. All
   CDK app commands below run inside `infra/`; the subshells return you to the
   repository root automatically. Running `cdk deploy` from the root instead
   would deploy the separate starter `PortfolioCdkStack`.

4. **Build and push both images to GHCR.** Create a GitHub PAT (classic)
   with `write:packages` and permission to publish to the target namespace.
   Run `docker login ghcr.io --username "$GITHUB_USER"` and paste the token
   at the password prompt. Do not use an AWS access key or commit the token.
   To publish to a different owner or repository, set `IMAGE_PREFIX` to that
   lowercase GHCR path and pass it to CDK as shown below.

   ```sh
   (
     set -e
     for svc in orders worker; do
       IMAGE="$IMAGE_PREFIX/$svc:$IMAGE_TAG"
       docker buildx build --platform linux/amd64 --load \
         --tag "$IMAGE" \
         --label "org.opencontainers.image.source=https://github.com/${IMAGE_PREFIX#ghcr.io/}" \
         "services/$svc"
       docker push "$IMAGE"
     done
   )
   ```

   On first publish, open each package's **Package settings > Change
   visibility** and set it to **Public**. Public packages are readable by
   anyone; publish only images intended for public distribution. Confirm
   both tags can be pulled without authentication before deploying:

   ```sh
   docker logout ghcr.io
   docker manifest inspect "$IMAGE_PREFIX/orders:$IMAGE_TAG" >/dev/null &&
     docker manifest inspect "$IMAGE_PREFIX/worker:$IMAGE_TAG" >/dev/null
   ```

   The platform uses x86-64 `t3.medium` instances, so `linux/amd64` is required
   even when building on Apple Silicon or another ARM computer. If CI has
   already published both desired tags, use them and skip the local build/push.

5. **Deploy all six test stacks.** Once both GHCR images are readable, CDK
   can deploy everything in one command and order the stacks by dependency:

   ```sh
   (
     cd infra
     cdk deploy --all \
       -c "env=$DEPLOY_ENV" -c "imagePrefix=$IMAGE_PREFIX" -c "imageTag=$IMAGE_TAG"
   )
   ```

   Review any CDK approval prompt and wait for ECS to stabilize. For subsequent
   image updates, publish a new tag and deploy again with it. Keep tags used
   by current and rollback task definitions available in GHCR.

6. **Get the endpoints and verify the running application:**

   ```sh
   export ALB_DNS="$(aws cloudformation describe-stacks \
     --stack-name "$DEPLOY_ENV-platform" \
     --query "Stacks[0].Outputs[?OutputKey=='AlbDns'].OutputValue | [0]" \
     --output text)"
   export FUNCTION_NAME="$(aws cloudformation describe-stacks \
     --stack-name "$DEPLOY_ENV-lambda-test" \
     --query "Stacks[0].Outputs[?OutputKey=='FunctionName'].OutputValue | [0]" \
     --output text)"
   export BUCKET_NAME="$(aws cloudformation describe-stacks \
     --stack-name "$DEPLOY_ENV-lambda-test" \
     --query "Stacks[0].Outputs[?OutputKey=='TestBucketName'].OutputValue | [0]" \
     --output text)"
   export LOG_GROUP_NAME="$(aws cloudformation describe-stacks \
     --stack-name "$DEPLOY_ENV-lambda-test" \
     --query "Stacks[0].Outputs[?OutputKey=='LogGroupName'].OutputValue | [0]" \
     --output text)"

   curl --fail "http://$ALB_DNS/orders/health"
   curl --fail "http://$ALB_DNS/lambda-test"
   aws ecs describe-services --cluster "$DEPLOY_ENV-cluster" \
     --services "$DEPLOY_ENV-orders" "$DEPLOY_ENV-worker" \
     --query 'services[].{Name:serviceName,Desired:desiredCount,Running:runningCount}'
   aws logs tail "/ecs/$DEPLOY_ENV/orders" --since 10m
   aws logs tail "/ecs/$DEPLOY_ENV/worker" --since 10m
   ```

   Expect `ok` from orders and a JSON test response from Lambda. An HTTP 404
   at `/` is intentional. Both ECS services should have running counts that
   match their desired counts. The Lambda and orders routes are public HTTP
   endpoints.

7. **Verify event delivery.** From the repository root, use the variables
   exported above:

   ```sh
   RESPONSE_FILE="$(mktemp)"
   aws lambda invoke --function-name "$FUNCTION_NAME" \
     --cli-binary-format raw-in-base64-out \
     --payload '{"message":"manual smoke test"}' "$RESPONSE_FILE"
   cat "$RESPONSE_FILE"
   rm "$RESPONSE_FILE"

   printf 'lambda smoke test\n' | aws s3 cp - "s3://$BUCKET_NAME/lambda-test/smoke.txt"
   aws logs tail "$LOG_GROUP_NAME" --since 10m

   QUEUE_URL="$(aws cloudformation list-stack-resources \
     --stack-name "$DEPLOY_ENV-messaging" \
     --query "StackResourceSummaries[?ResourceType=='AWS::SQS::Queue' && starts_with(LogicalResourceId, 'OrderQueue')].PhysicalResourceId | [0]" \
     --output text)"
   printf 'messaging smoke test\n' | aws s3 cp - "s3://$BUCKET_NAME/incoming/smoke.txt"
   aws sqs receive-message --queue-url "$QUEUE_URL" --wait-time-seconds 20 --visibility-timeout 0
   ```

   Expect `eventSource: manual` in the direct response and `source=aws.s3`
   in the Lambda logs after the upload under `lambda-test/`. Allow up to
   five minutes for S3 EventBridge enablement; upload again after that if
   needed. The queue response should contain the SNS notification for the
   `incoming/` upload. Poll again if delivery hasn't completed.

   To verify the live EC2 EventBridge rule, look for `source=aws.ec2` in the
   same logs after a state change in the target account and region. Observe
   a normal lifecycle event or start/stop a separate disposable instance you
   own; don't stop the ECS hosts. If you create a separate instance, terminate
   it and check for retained volumes afterward because CDK does not manage it.
   The [synthetic EC2 example](#test-lambda-service) tests the handler only,
   not EventBridge delivery.

   When finished, follow [Destroy an AWS test deployment](#destroy-an-aws-test-deployment).

### Destroy an AWS test deployment

From the repository root, run the teardown script with the same profile,
account, region, and exact `test-*` prefix printed by deployment:

```sh
bash scripts/destroy-test.sh --profile dev --account 123456789012 \
  --region ap-south-1 --env test-YOUR-TIMESTAMP
```

Add `--sso` if the profile needs an SSO login. The script requires AWS CLI v2
and `jq`. It previews what it will delete and asks you to type the exact test
prefix. It handles partial deployments, waits for stack deletion, and removes
the recorded test bucket (including all versions and delete markers),
surviving log groups, and any legacy test ECR repositories. It leaves
`CDKToolkit`, GHCR packages, and manually created resources untouched. Use
the checklist below for manual teardown or for a shared `dev` deployment.

For an account/region used **only for this test**, optionally remove
`CDKToolkit` and its retained asset bucket/ECR repository as well:

```sh
bash scripts/destroy-test.sh --profile dev --account 123456789012 \
  --region ap-south-1 --env test-YOUR-TIMESTAMP --delete-bootstrap
```

This requires a second confirmation, deletes bootstrap last, and disables
its termination protection if necessary. The script refuses if other active
CloudFormation stacks exist in the region, but it cannot detect every external
pipeline or cross-account bootstrap consumer. Confirm that none exist before
proceeding. All versions in the recorded bootstrap asset bucket and all images
in its ECR repository are permanently deleted. Future CDK deployments will
need bootstrapping again. GHCR packages are not affected.

These steps permanently delete test resources and data. Reuse the same AWS
profile, region, and exact `DEPLOY_ENV` from deployment. If you used `dev`,
disable automated deployments to it and confirm no one else is using it.
Do not use these instructions for `prod`.

1. **Record resource names before teardown.** Confirm the account, region,
   and prefix. Save the physical IDs for the uploads bucket, CloudWatch log
   groups, and Lambda functions. Some CDK-managed Lambda functions can leave
   log groups behind. If the deployment was partial, inventory whichever
   stacks were created. If a stack was already deleted, look it up under
   **Deleted** stacks in CloudFormation while its resource history is available.

   ```sh
   aws sts get-caller-identity
   printf 'Region: %s | Environment: %s\n' "$AWS_REGION" "$DEPLOY_ENV"
   for stack in network messaging platform lambda-test svc-orders svc-worker; do
     aws cloudformation list-stack-resources --stack-name "$DEPLOY_ENV-$stack" \
       --query "StackResourceSummaries[?ResourceType=='AWS::S3::Bucket' || ResourceType=='AWS::Logs::LogGroup' || ResourceType=='AWS::Lambda::Function'].[ResourceType,PhysicalResourceId]" \
       --output table
   done
   ```

2. **Destroy the application stacks.** From the repository root, delete the
   service and Lambda stacks before the infrastructure they reference.
   Review and accept only the prompts for your test stacks. For a partial
   deployment, target only the stacks that exist. If deletion fails,
   inspect that stack's CloudFormation events and resolve the blocker before
   continuing.

   ```sh
   (
     set -e
     : "${DEPLOY_ENV:?Set DEPLOY_ENV to the exact test environment you deployed}"
     if [ "$DEPLOY_ENV" = prod ]; then
       echo 'Refusing to destroy prod.' >&2
       exit 1
     fi
     cd infra
     cdk destroy "$DEPLOY_ENV-svc-orders" "$DEPLOY_ENV-svc-worker" "$DEPLOY_ENV-lambda-test" -c "env=$DEPLOY_ENV"
     cdk destroy "$DEPLOY_ENV-platform" "$DEPLOY_ENV-messaging" -c "env=$DEPLOY_ENV"
     cdk destroy "$DEPLOY_ENV-network" -c "env=$DEPLOY_ENV"
   )
   ```

3. **Remove retained test data.** In the S3 console, verify the recorded
   uploads bucket name, **Empty** the bucket, then **Delete** it. It's
   versioned: emptying must remove every object version and delete marker,
   not just current objects. In **CloudWatch > Log groups**, delete any
   surviving groups from the inventory, including `/aws/lambda/<function-name>`
   for recorded functions that created their own logs. The ECS drain-hook
   log group is retained by CDK. If you previously deployed the ECR version
   of this app, remove any retained test ECR repositories only after checking
   no other deployment or rollback uses them. GHCR images are outside AWS;
   delete only disposable test versions, not tags used by other environments.

4. **Verify cleanup.** Check that no test stacks remain, including
   `DELETE_FAILED` stacks:

   ```sh
   aws cloudformation list-stacks \
     --query "StackSummaries[?starts_with(StackName, '${DEPLOY_ENV}-') && StackStatus!='DELETE_COMPLETE'].[StackName,StackStatus]" \
     --output table
   ```

   Also confirm the test NAT gateway, load balancer, EC2 instances, EBS
   volumes, and Elastic IPs are gone and that retained buckets/log groups
   were removed. Don't delete unrelated resources. AWS billing reports can
   lag behind deletion, and cleanup won't reverse charges already incurred.

**Bootstrap resources:** Leave `CDKToolkit` alone if it predates the test or
is used by any other stacks or pipelines. If it was created solely for this
test in a disposable account/region, and nothing else depends on it, record
its asset bucket and ECR repository in the CloudFormation **Resources** tab.
Remove termination protection on `CDKToolkit` if enabled, delete that stack,
wait for deletion to finish, then empty and delete any retained asset bucket
and ECR repository from that inventory. The bucket must be cleared of versions
and delete markers as well. Future CDK deployments to that account/region will
need bootstrapping again.

## Configure GitHub Actions and AWS

On `master`, the workflow runs **verify → publish to GHCR → deploy dev →
deploy prod**. Both environments use the same full-commit-SHA image tags.
Feature-branch pushes and pull requests only verify (including Docker builds
and CDK synthesis). Use **Actions > ci-cd > Run workflow** on `master` to
trigger the same release sequence manually.

1. **Enable Actions.** In this repository, open **Settings > Actions > General**
   and enable GitHub Actions. If you restrict allowed actions, allow
   `actions/checkout`, `actions/setup-java`, `actions/setup-node`,
   `actions/setup-python`, and `aws-actions/configure-aws-credentials`.
   The publish job uses the built-in `GITHUB_TOKEN` with `packages: write`;
   no GHCR PAT secret is needed for CI.

2. **Create GitHub environments.** Under **Settings > Environments**, create
   environments named exactly `dev` and `prod`. Restrict their deployment
   branches to `master`. On `prod`, configure **Required reviewers** and
   **Prevent self-review** where your GitHub plan/repository visibility supports
   them. Naming an environment `prod` alone does not create an approval gate.
   If required reviewers are unavailable, this workflow will deploy prod
   automatically after dev succeeds.

3. **Configure AWS OIDC.** In each target AWS account, add an IAM OpenID Connect
   identity provider if one does not already exist:
   provider URL `https://token.actions.githubusercontent.com`,
   audience `sts.amazonaws.com`. Create a deployment IAM role for each
   environment. Use the following trust policy, substituting the account ID
   and environment name (`dev` below, `prod` for the production role):

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": {
         "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
       },
       "Action": "sts:AssumeRoleWithWebIdentity",
       "Condition": {
         "StringEquals": {
           "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
           "token.actions.githubusercontent.com:sub": "repo:rohit-sahu/portfolio-infra:environment:dev"
         }
       }
     }]
   }
   ```

   The environment-based subject is intentional: it is not
   `repo:rohit-sahu/portfolio-infra:ref:refs/heads/master`. Enforce branch access
   through the GitHub environment deployment restrictions above. Separate AWS
   accounts for dev and prod are recommended.

4. **Check CDK bootstrap once per target account/region.** Skip this step if
   the region already has a compatible `CDKToolkit` stack. Otherwise, use
   administrator-approved credentials for that AWS account and run:

   ```sh
   aws sts get-caller-identity
   ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
   npx --yes --package aws-cdk@2.1148.1 cdk bootstrap "aws://$ACCOUNT_ID/ap-south-1"
   ```

   This creates `CDKToolkit`, the asset bucket for the VPC's custom-resource
   Lambda, and CDK deployment roles. The workflow does not bootstrap on push. The
   default modern bootstrap grants its CloudFormation execution role broad
   deployment permissions; use an organization-approved execution policy for
   production. Do not put an administrator access key in GitHub.

5. **Authorize the GitHub OIDC deployment roles.** The trust policy above only
   allows login; each role also needs an IAM permissions policy. For the
   default CDK bootstrap qualifier `hnb659fds`, allow `sts:AssumeRole` on these
   bootstrap roles in its target account and `ap-south-1`:
   `cdk-hnb659fds-deploy-role-<ACCOUNT_ID>-ap-south-1`,
   `cdk-hnb659fds-file-publishing-role-<ACCOUNT_ID>-ap-south-1`,
   `cdk-hnb659fds-image-publishing-role-<ACCOUNT_ID>-ap-south-1`, and
   `cdk-hnb659fds-lookup-role-<ACCOUNT_ID>-ap-south-1`.
   Their ARNs start with `arn:aws:iam::<ACCOUNT_ID>:role/`.
   The bootstrap roles must trust the deployment role's account.
   Allow `cloudformation:DescribeStacks` on the `CDKToolkit` and
   `<environment>-*` stacks for CDK discovery, and `ssm:GetParameter` on
   `/cdk-bootstrap/hnb659fds/version` for bootstrap-version checks.
   The OIDC role does not need ECR push permissions. GitHub publishes to GHCR
   independently of AWS, and ECS pulls the public images anonymously.
   Adapt these permissions if you use a custom qualifier, region, account, or
   bootstrap trust configuration; resource creation permissions belong on the
   CloudFormation execution role.

6. **Set environment secrets.** In each GitHub environment, under
   **Environment secrets**, add `AWS_ROLE_ARN` containing that environment's
   OIDC deployment role ARN. It is consumed by the reusable deployment
   workflow through its selected environment; no `secrets: inherit` is needed.
   No AWS access-key or secret-key secrets are required. The region is
   `ap-south-1` in both workflow files; change both if deploying elsewhere and
   bootstrap that region.

7. **Protect and push `master`.** Configure a branch protection rule or
   ruleset requiring pull requests and the `verify` status check before merge
   (the check becomes selectable after its first run). Commit and push the
   workflows together with all CDK and service source files. On the first
   publish, GHCR packages are private by default. After both packages appear,
   set `portfolio-infra/orders` and `portfolio-infra/worker` to **Public** in
   their package settings. If they already exist, ensure this repository has
   write access under **Manage Actions access** for each package. The workflow
   stops before AWS if it cannot read both tags anonymously; after setting
   visibility, rerun failed jobs. Monitor **Actions > ci-cd** and use
   **Review deployments** for the configured prod gate.
   If you rename the default branch to `main`, also update the publish condition
   in `ci-cd.yml` and the GitHub environment branch restrictions.

These workflows create billable AWS resources, including EC2 instances, a NAT
gateway, and an ALB. Prod stacks have termination protection, and the uploads
bucket retains data on stack deletion. A failed deployment can leave
shared infrastructure running; GitHub workflow cancellation is not cleanup.

## Test Lambda service

The `infra/` app also includes a `<env>-lambda-test` stack with a Python 3.13
test handler in `services/lambda-test/index.py`. It uses only the standard
library; Maven packages its source as a CDK resource, so no Docker build or
separate Python dependencies are required.

| Trigger | Behavior |
|---------|----------|
| Manual invocation | Accepts a dummy JSON object and returns a test response. |
| S3 | Object creation under `lambda-test/` in the uploads bucket invokes Lambda through EventBridge. Existing `incoming/` SNS/SQS notifications are unchanged. |
| EC2 | Instance state-change notifications in the deployment account and region invoke Lambda through EventBridge, including changes to instances outside this app. |
| ALB | `/lambda-test` and `/lambda-test/*` on the existing public HTTP listener return a JSON test response. This test route has no authentication. |

The handler logs only the event source and request ID. It does not read object
contents, process orders, or modify EC2 instances. Logs expire after one week.
Failed asynchronous Lambda executions are retried twice and then sent to the
stack's dead-letter queue; this does not cover failed synchronous ALB requests
or EventBridge delivery failures.

Deploy from `infra/` (CDK includes the shared stack dependencies):

```sh
cdk deploy dev-lambda-test -c env=dev
```

Use the stack outputs `FunctionName`, `TestBucketName`, `LogGroupName`, and
`FailedEventsQueueUrl`, plus `AlbDns` from `dev-platform`, for testing. Set
`FUNCTION_NAME`, `BUCKET_NAME`, `LOG_GROUP_NAME`, and `ALB_DNS` to these values,
and use AWS CLI credentials for the same account and region:

```sh
aws lambda invoke --function-name "$FUNCTION_NAME" \
  --cli-binary-format raw-in-base64-out \
  --payload '{"message":"hello from a dummy event"}' /tmp/lambda-test-response.json
cat /tmp/lambda-test-response.json

aws s3 cp ../README.md "s3://$BUCKET_NAME/lambda-test/readme.txt"
curl --fail "http://$ALB_DNS/lambda-test"
aws logs tail "$LOG_GROUP_NAME" --since 10m
```

S3 EventBridge enablement can take about five minutes to propagate after the
initial deployment. S3 and EC2 events can be delivered more than once.
Observe EC2 events during normal instance lifecycle changes; no instance needs
to be stopped just to test the handler. To test its EC2 response without
changing an instance, manually invoke it with a synthetic event:

```sh
aws lambda invoke --function-name "$FUNCTION_NAME" \
  --cli-binary-format raw-in-base64-out \
  --payload '{"source":"aws.ec2","detail-type":"EC2 Instance State-change Notification","detail":{"instance-id":"i-example","state":"running"}}' \
  /tmp/lambda-test-response.json
```

Run the handler tests from the repository root with:

```sh
python3 -B -m unittest discover -s services/lambda-test
```
