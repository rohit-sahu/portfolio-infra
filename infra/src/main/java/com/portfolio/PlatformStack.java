package com.portfolio;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class PlatformStack extends Stack {
    private final Cluster cluster;
    private final ApplicationListener listener;
    private final String capacityProviderName;

    public PlatformStack(Construct scope, String id, StackProps props,
                         String envName, IVpc vpc) {
        super(scope, id, props);

        cluster = Cluster.Builder.create(this, "Cluster")
                .clusterName(envName + "-cluster").vpc(vpc).build();

        // ---- IAM: role for the EC2 hosts -----------------------------------
        // Trusts ec2.amazonaws.com. The ASG below wraps this role in an
        // AWS::IAM::InstanceProfile automatically (that is the InstanceProfile).
        Role instanceRole = Role.Builder.create(this, "InstanceRole")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName(
                                "service-role/AmazonEC2ContainerServiceforEC2Role"), // ECS agent, ECR pull, logs
                        ManagedPolicy.fromAwsManagedPolicyName(
                                "AmazonSSMManagedInstanceCore")))                    // shell via SSM, no SSH
                .build();

        // ---- ASG of ECS container instances in PRIVATE subnets -------------
        SecurityGroup instanceSg = SecurityGroup.Builder.create(this, "InstanceSg")
                .vpc(vpc).description("ECS container instances").allowAllOutbound(true).build();

        AutoScalingGroup asg = AutoScalingGroup.Builder.create(this, "Asg")
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
                .instanceType(new InstanceType("t3.medium"))
                .machineImage(EcsOptimizedImage.amazonLinux2023())
                .role(instanceRole)             // -> CDK creates the InstanceProfile
                .securityGroup(instanceSg)
                .minCapacity(2).maxCapacity(6)
                .requireImdsv2(true)            // blocks SSRF-style credential theft
                .build();

        // Capacity provider = ECS scales the ASG for you when tasks don't fit
        AsgCapacityProvider provider = AsgCapacityProvider.Builder.create(this, "Capacity")
                .autoScalingGroup(asg)
                .enableManagedTerminationProtection(false) // lets `cdk destroy` work; consider true in prod
                .build();
        cluster.addAsgCapacityProvider(provider);
        capacityProviderName = provider.getCapacityProviderName();

        // ---- ALB in PUBLIC subnets ------------------------------------------
        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "Alb")
                .vpc(vpc)
                .internetFacing(true)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .build();

        listener = alb.addListener("Http", BaseApplicationListenerProps.builder()
                .port(80).open(true)
                .defaultAction(ListenerAction.fixedResponse(404, FixedResponseOptions.builder()
                        .contentType("text/plain").messageBody("Not found").build()))
                .build());   // add an HTTPS 443 listener + ACM cert in prod

        // Containers use bridge networking with dynamic host ports, so the ALB
        // must reach the ephemeral port range on the instances
        instanceSg.getConnections().allowFrom(alb, Port.tcpRange(32768, 65535),
                "ALB to ECS dynamic host ports");

        CfnOutput.Builder.create(this, "AlbDns").value(alb.getLoadBalancerDnsName()).build();
    }

    public Cluster getCluster()               { return cluster; }
    public ApplicationListener getListener()  { return listener; }
    public String getCapacityProviderName()   { return capacityProviderName; }
}
