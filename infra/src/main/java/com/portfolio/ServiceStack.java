package com.portfolio;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck; // disambiguate from ecs.HealthCheck
import software.amazon.awscdk.services.iam.IGrantable;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

public class ServiceStack extends Stack {

    public ServiceStack(Construct scope, String id, StackProps props, String envName,
                        ServiceSpec spec, Cluster cluster, String capacityProvider,
                        IApplicationListener listener, String imageUri,
                        Map<String, String> environment, Consumer<IGrantable> grants) {
        super(scope, id, props);

        // ---- Task definition: bridge mode = many tasks per EC2 host ---------
        Ec2TaskDefinition task = Ec2TaskDefinition.Builder.create(this, "Task")
                .networkMode(NetworkMode.BRIDGE).build();

        // Application permissions go on the TaskRole (not on the instance role)
        grants.accept(task.getTaskRole());

        LogGroup logs = LogGroup.Builder.create(this, "Logs")
                .logGroupName("/ecs/" + envName + "/" + spec.name())
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(RemovalPolicy.DESTROY).build();

        ContainerDefinition container = task.addContainer("app",
                ContainerDefinitionOptions.builder()
                        .image(ContainerImage.fromRegistry(imageUri))
                        .cpu(spec.cpu())
                        .memoryLimitMiB(spec.memoryMiB())
                        .essential(true)
                        .environment(environment)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .logGroup(logs).streamPrefix(spec.name()).build()))
                        .build());

        if (spec.exposed()) {
            // no hostPort => dynamic port; the ALB discovers it via the target group
            container.addPortMappings(PortMapping.builder().containerPort(8080).build());
        }

        // ---- ECS service on the EC2 capacity provider -----------------------
        Ec2Service service = Ec2Service.Builder.create(this, "Service")
                .serviceName(envName + "-" + spec.name())
                .cluster(cluster)
                .taskDefinition(task)
                .desiredCount(spec.desiredCount())
                .minHealthyPercent(100)
                .maxHealthyPercent(200)
                .circuitBreaker(DeploymentCircuitBreaker.builder().rollback(true).build())
                .capacityProviderStrategies(List.of(CapacityProviderStrategy.builder()
                        .capacityProvider(capacityProvider).weight(1).build()))
                .build();

        // ---- Public routing, only for services that have a path ------------
        if (spec.exposed()) {
            ApplicationTargetGroup tg = ApplicationTargetGroup.Builder.create(this, "Tg")
                    .vpc(cluster.getVpc())
                    .protocol(ApplicationProtocol.HTTP)
                    .port(8080)
                    .targetType(TargetType.INSTANCE)
                    .healthCheck(HealthCheck.builder()
                            .path(spec.healthPath()).healthyHttpCodes("200")
                            .interval(Duration.seconds(30)).build())
                    .deregistrationDelay(Duration.seconds(30))
                    .build();
            service.attachToApplicationTargetGroup(tg);

            // The rule lives in THIS stack, so the platform stack never depends on services
            ApplicationListenerRule.Builder.create(this, "Rule")
                    .listener(listener)
                    .priority(spec.priority())
                    .conditions(List.of(ListenerCondition.pathPatterns(List.of(spec.pathPattern()))))
                    .action(ListenerAction.forward(List.of(tg)))
                    .build();
        }

        // ---- Task-level autoscaling -----------------------------------------
        service.autoScaleTaskCount(EnableScalingProps.builder()
                        .minCapacity(spec.desiredCount()).maxCapacity(spec.desiredCount() * 3).build())
                .scaleOnCpuUtilization("Cpu", CpuUtilizationScalingProps.builder()
                        .targetUtilizationPercent(60).build());
    }
}
