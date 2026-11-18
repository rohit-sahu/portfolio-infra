package com.portfolio;

import java.util.List;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

public class NetworkStack extends Stack {
    private final Vpc vpc;

    public NetworkStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(1)   // use 1 per AZ in prod for HA
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder().name("public")
                                .subnetType(SubnetType.PUBLIC).cidrMask(24).build(),     // ALB, NAT
                        SubnetConfiguration.builder().name("private")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS).cidrMask(24).build())) // EC2 hosts
                .build();

        // S3 traffic skips the NAT gateway (cheaper, and stays on the AWS network)
        vpc.addGatewayEndpoint("S3Endpoint", GatewayVpcEndpointOptions.builder()
                .service(GatewayVpcEndpointAwsService.S3).build());
    }

    public Vpc getVpc() { return vpc; }
}
