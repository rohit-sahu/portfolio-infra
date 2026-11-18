package com.portfolio;

import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.Queue;

public class PortfolioCdkStack extends Stack {
    public PortfolioCdkStack(final Construct parent, final String id) {
        this(parent, id, null);
    }

    public PortfolioCdkStack(final Construct parent, final String id, final StackProps props) {
        super(parent, id, props);

        // Inside your Stack constructor
        Bucket.Builder.create(this, "MyBucket")
                .removalPolicy(RemovalPolicy.DESTROY) // Deletes the bucket instead of retaining it
                .autoDeleteObjects(true)              // Automatically empties the bucket before deletion
                .build();

        final Queue queue = Queue.Builder.create(this, "PortfolioCdkQueue")
                .visibilityTimeout(Duration.seconds(300))
                .removalPolicy(RemovalPolicy.DESTROY) // Deletes the bucket instead of retaining it
                .build();

        final Topic topic = Topic.Builder.create(this, "PortfolioCdkTopic")
            .displayName("My First Topic Yeah")
            .build();

        topic.addSubscription(new SqsSubscription(queue));
    }
}
