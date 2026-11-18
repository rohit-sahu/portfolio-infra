# Infrastructure CDK app

See the repository [README](../README.md) for the architecture overview, prerequisites, and deploy workflow. Run CDK commands from this directory; the app entry point is `com.portfolio.InfraApp`.

The `orders` and `worker` images are placeholders required for deployment wiring.
ECS pulls them anonymously from public GHCR packages. For ECS deployments, pass
`-c imageTag=<published-tag>` and, if using another namespace, `-c imagePrefix=<ghcr-prefix>`.
No application ECR repositories are created. A compatible CDK bootstrap stack
is still required for the VPC default-security-group custom-resource asset.

`<env>-lambda-test` adds a test Lambda for manual invocation, S3 object-created
events under `lambda-test/`, EC2 state-change events, and the ALB's `/lambda-test`
route. Its Python source is included from `../services/lambda-test` by Maven.
See the root README for deployment, example invocations, and trigger scope.
