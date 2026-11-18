package com.portfolio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.IApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerAction;
import software.amazon.awscdk.services.elasticloadbalancingv2.ListenerCondition;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.LambdaTarget;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.IBucket;
import software.constructs.Construct;

public class LambdaServiceStack extends Stack {
    public LambdaServiceStack(Construct scope, String id, StackProps props, String envName,
                              IBucket bucket, IApplicationListener listener) {
        super(scope, id, props);

        LogGroup logs = LogGroup.Builder.create(this, "Logs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Function function = Function.Builder.create(this, "Handler")
                .description("Dummy service for manual, S3, EC2 and ALB event testing")
                .runtime(Runtime.PYTHON_3_13)
                .handler("index.handler")
                .code(Code.fromInline(handlerCode()))
                .memorySize(128)
                .timeout(Duration.seconds(10))
                .logGroup(logs)
                .deadLetterQueueEnabled(true)
                .retryAttempts(2)
                .environment(Map.of("ENV_NAME", envName))
                .build();

        // EventBridge avoids a bucket notification dependency back to this stack.
        Rule.Builder.create(this, "S3Created")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.s3"))
                        .detailType(List.of("Object Created"))
                        .detail(Map.of(
                                "bucket", Map.of("name", List.of(bucket.getBucketName())),
                                "object", Map.of("key", List.of(Map.of("prefix", "lambda-test/")))))
                        .build())
                .targets(List.of(new LambdaFunction(function)))
                .build();

        Rule.Builder.create(this, "Ec2StateChange")
                .eventPattern(EventPattern.builder()
                        .source(List.of("aws.ec2"))
                        .detailType(List.of("EC2 Instance State-change Notification"))
                        .build())
                .targets(List.of(new LambdaFunction(function)))
                .build();

        ApplicationTargetGroup targetGroup = ApplicationTargetGroup.Builder.create(this, "TargetGroup")
                .targetType(TargetType.LAMBDA)
                .targets(List.of(new LambdaTarget(function)))
                .build();
        ApplicationListenerRule.Builder.create(this, "Rule")
                .listener(listener)
                .priority(1000)
                .conditions(List.of(ListenerCondition.pathPatterns(
                        List.of("/lambda-test", "/lambda-test/*"))))
                .action(ListenerAction.forward(List.of(targetGroup)))
                .build();

        CfnOutput.Builder.create(this, "FunctionName").value(function.getFunctionName()).build();
        CfnOutput.Builder.create(this, "TestBucketName").value(bucket.getBucketName()).build();
        CfnOutput.Builder.create(this, "LogGroupName").value(logs.getLogGroupName()).build();
        CfnOutput.Builder.create(this, "FailedEventsQueueUrl")
                .value(function.getDeadLetterQueue().getQueueUrl()).build();
    }

    private static String handlerCode() {
        try (InputStream input = LambdaServiceStack.class.getResourceAsStream("/lambda-test/index.py")) {
            if (input == null) {
                throw new IllegalStateException("Missing Lambda handler resource: /lambda-test/index.py");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Lambda handler", e);
        }
    }
}
