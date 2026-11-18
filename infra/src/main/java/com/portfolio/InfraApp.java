package com.portfolio;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.iam.IGrantable;

public class InfraApp {
    // Adding a microservice = one line here + a folder under services/
    static final List<ServiceSpec> SERVICES = List.of(
            new ServiceSpec("orders", "/orders/*", 10, 256, 512, 2, "/orders/health"),
            new ServiceSpec("worker", null,        0,  256, 512, 1, null));

    public static void main(String[] args) {
        App app = new App();
        defineStacks(app);
        app.synth();
    }

    static void defineStacks(App app) {
        String envName  = ctx(app, "env", "dev");       // -c env=prod
        String imageTag = ctx(app, "imageTag", "latest"); // ECS deploys must specify a published tag
        String imagePrefix = ctx(app, "imagePrefix", "ghcr.io/rohit-sahu/portfolio-infra");

        StackProps props = StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .terminationProtection(envName.equals("prod"))
                .build();

        NetworkStack network     = new NetworkStack(app, envName + "-network", props);
        MessagingStack messaging = new MessagingStack(app, envName + "-messaging", props);
        PlatformStack platform   = new PlatformStack(app, envName + "-platform", props,
                envName, network.getVpc());
        new LambdaServiceStack(app, envName + "-lambda-test", props, envName,
                messaging.getBucket(), platform.getListener());

        // Least privilege: each service gets only what it needs on its own TaskRole
        Map<String, Consumer<IGrantable>> grants = Map.of(
                "orders", g -> {
                    messaging.getBucket().grantReadWrite(g);
                    messaging.getTopic().grantPublish(g);
                },
                "worker", g -> {
                    messaging.getQueue().grantConsumeMessages(g);
                    messaging.getBucket().grantRead(g);
                });

        // Config injected into containers as environment variables
        Map<String, Map<String, String>> envVars = Map.of(
                "orders", Map.of(
                        "BUCKET_NAME", messaging.getBucket().getBucketName(),
                        "TOPIC_ARN",   messaging.getTopic().getTopicArn()),
                "worker", Map.of(
                        "QUEUE_URL",   messaging.getQueue().getQueueUrl(),
                        "BUCKET_NAME", messaging.getBucket().getBucketName()));

        for (ServiceSpec spec : SERVICES) {
            new ServiceStack(app, envName + "-svc-" + spec.name(), props, envName, spec,
                    platform.getCluster(), platform.getCapacityProviderName(),
                    platform.getListener(), imagePrefix + "/" + spec.name() + ":" + imageTag,
                    envVars.get(spec.name()), grants.get(spec.name()));
        }

        Tags.of(app).add("env", envName);
    }

    private static String ctx(App app, String key, String dflt) {
        Object v = app.getNode().tryGetContext(key);
        return v == null ? dflt : v.toString();
    }
}
