package com.portfolio;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class LambdaServiceStackTest {
    @Test
    void createsLambdaWithEventRulesAndAlbRouting() {
        App app = new App();
        StackProps props = StackProps.builder().build();
        NetworkStack network = new NetworkStack(app, "network", props);
        MessagingStack messaging = new MessagingStack(app, "messaging", props);
        PlatformStack platform = new PlatformStack(app, "platform", props, "test",
                network.getVpc());
        LambdaServiceStack stack = new LambdaServiceStack(app, "lambda", props, "test",
                messaging.getBucket(), platform.getListener());
        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::Lambda::Function", 1);
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
                "Runtime", "python3.13",
                "Handler", "index.handler",
                "Timeout", 10,
                "Environment", Map.of("Variables", Map.of("ENV_NAME", "test")),
                "DeadLetterConfig", Match.objectLike(Map.of("TargetArn", Match.anyValue()))));
        template.hasResourceProperties("AWS::Events::Rule", Map.of(
                "EventPattern", Map.of(
                        "source", List.of("aws.s3"),
                        "detail-type", List.of("Object Created"),
                        "detail", Map.of(
                                "bucket", Map.of("name", List.of(Match.anyValue())),
                                "object", Map.of("key", List.of(Map.of("prefix", "lambda-test/"))))),
                "Targets", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Arn", Match.anyValue()))))));
        template.hasResourceProperties("AWS::Events::Rule", Map.of(
                "EventPattern", Map.of(
                        "source", List.of("aws.ec2"),
                        "detail-type", List.of("EC2 Instance State-change Notification"))));
        template.resourceCountIs("AWS::Events::Rule", 2);
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup",
                Map.of("TargetType", "lambda", "Targets", Match.anyValue()));
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::ListenerRule", Map.of(
                "Priority", 1000,
                "Conditions", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Field", "path-pattern",
                        "PathPatternConfig", Map.of("Values", List.of("/lambda-test", "/lambda-test/*"))))))));
        template.hasResourceProperties("AWS::Lambda::Permission",
                Map.of("Principal", "elasticloadbalancing.amazonaws.com"));
        template.hasResourceProperties("AWS::Lambda::Permission",
                Map.of("Principal", "events.amazonaws.com"));

        // Synthesize the complete app to detect cross-stack dependency cycles.
        app.synth();
    }
}
