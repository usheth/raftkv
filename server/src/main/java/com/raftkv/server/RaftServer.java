package com.raftkv.server;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * gRPC server lifecycle wrapper.
 *
 * <p>Hosts one or more {@link BindableService}s (typically {@link RaftServiceImpl} and
 * {@link KvServiceImpl}) and manages {@code start()} / {@code stop()} lifecycle.
 *
 * <p>Pass port {@code 0} to let the OS assign an ephemeral port; call {@link #getPort()}
 * after {@link #start()} to retrieve the actual bound port.
 */
public class RaftServer {

    private static final Logger log = LoggerFactory.getLogger(RaftServer.class);

    private final int requestedPort;
    private final List<BindableService> services;

    private Server server;

    /**
     * @param port     the port to bind (0 for OS-assigned ephemeral port)
     * @param services gRPC service implementations to register
     */
    public RaftServer(int port, List<BindableService> services) {
        this.requestedPort = port;
        this.services = List.copyOf(services);
    }

    /**
     * Starts the gRPC server, binding to the configured port.
     *
     * @throws IOException if the server cannot bind
     */
    public void start() throws IOException {
        ServerBuilder<?> builder = ServerBuilder.forPort(requestedPort);
        for (BindableService svc : services) {
            builder.addService(svc);
        }
        builder.addService(ProtoReflectionService.newInstance());
        server = builder.build().start();
        log.info("RaftServer started on port {}", server.getPort());
    }

    /**
     * Stops the server, waiting for in-flight RPCs to complete.
     */
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
            log.info("RaftServer stopped");
        }
    }

    /**
     * Returns the actual port the server is bound to.
     * Only valid after {@link #start()} has been called.
     *
     * @throws IllegalStateException if the server has not been started yet
     */
    public int getPort() {
        if (server == null) {
            throw new IllegalStateException("Server has not been started");
        }
        return server.getPort();
    }
}
