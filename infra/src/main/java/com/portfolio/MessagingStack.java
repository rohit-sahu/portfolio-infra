package com.portfolio;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.s3.notifications.SnsDestination;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

public class MessagingStack extends Stack {
    private final Bucket bucket;
    private final Topic topic;
    private final Queue queue;

    public MessagingStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        bucket = Bucket.Builder.create(this, "Uploads")
                .versioned(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .removalPolicy(RemovalPolicy.RETAIN)   // never delete data on stack delete
                .build();

        topic = Topic.Builder.create(this, "OrderEvents").build();

        Queue dlq = Queue.Builder.create(this, "OrderDlq")
                .retentionPeriod(Duration.days(14)).build();

        queue = Queue.Builder.create(this, "OrderQueue")
                .visibilityTimeout(Duration.seconds(60))   // > worker processing time
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(dlq).maxReceiveCount(5).build())
                .build();

        // SNS -> SQS fan-out (CDK also adds the queue policy that allows SNS to send)
        topic.addSubscription(new SqsSubscription(queue));

        // S3 -> SNS on new objects under incoming/ (CDK adds the topic policy)
        bucket.addEventNotification(EventType.OBJECT_CREATED, new SnsDestination(topic),
                NotificationKeyFilter.builder().prefix("incoming/").build());
    }

    public Bucket getBucket() { return bucket; }
    public Topic getTopic()   { return topic; }
    public Queue getQueue()   { return queue; }
}
