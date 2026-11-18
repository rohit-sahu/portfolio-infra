package com.portfolio;

import software.amazon.awscdk.App;

public final class PortfolioCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        new PortfolioCdkStack(app, "PortfolioCdkStack");

        app.synth();
    }
}
