package com.portfolio;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class MessagingStackTest {
    @Test
    void createsBucketTopicAndQueues() {
        App app = new App();
        MessagingStack stack = new MessagingStack(app, "test", StackProps.builder().build());
        Template t = Template.fromStack(stack);

        t.resourceCountIs("AWS::SNS::Topic", 1);
        t.resourceCountIs("AWS::SQS::Queue", 2);   // main queue + DLQ
        t.hasResourceProperties("AWS::S3::Bucket",
                Map.of("VersioningConfiguration", Map.of("Status", "Enabled")));
        t.hasResourceProperties("Custom::S3BucketNotifications", Map.of(
                "NotificationConfiguration", Match.objectLike(
                        Map.of("EventBridgeConfiguration", Map.of()))));
    }
}
