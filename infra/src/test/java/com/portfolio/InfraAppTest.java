package com.portfolio;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class InfraAppTest {
    @Test
    void deploysBothServicesFromPublicGhcrWithoutCreatingEcrRepositories() {
        App app = new App(AppProps.builder().context(Map.of(
                "env", "dev",
                "imagePrefix", "ghcr.io/example/portfolio",
                "imageTag", "test-sha")).build());
        InfraApp.defineStacks(app);

        Template.fromStack((Stack) app.getNode().findChild("dev-platform"))
                .resourceCountIs("AWS::ECR::Repository", 0);

        for (String service : List.of("orders", "worker")) {
            Template.fromStack((Stack) app.getNode().findChild("dev-svc-" + service))
                    .hasResourceProperties("AWS::ECS::TaskDefinition", Map.of(
                            "ContainerDefinitions", Match.arrayWith(List.of(Match.objectLike(Map.of(
                                    "Name", "app",
                                    "Image", "ghcr.io/example/portfolio/" + service + ":test-sha",
                                    "RepositoryCredentials", Match.absent()))))));
        }
    }
}
