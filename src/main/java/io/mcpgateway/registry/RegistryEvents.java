package io.mcpgateway.registry;

/**
 * Port for broadcasting registry mutations to every gateway node (FR-REG-3). The Redis adapter
 * is the default; the interface exists so tests — and future transports — swap in cleanly.
 */
public interface RegistryEvents {

    /** Announces that a server's registration state changed and cached routes for it are stale. */
    void serverChanged(String serverName);
}
