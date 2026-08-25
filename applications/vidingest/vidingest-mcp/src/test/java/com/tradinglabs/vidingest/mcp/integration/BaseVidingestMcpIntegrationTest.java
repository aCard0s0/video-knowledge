package com.tradinglabs.vidingest.mcp.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseVidingestMcpIntegrationTest {

    @LocalServerPort
    protected int port;
}

