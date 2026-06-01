package io.github.qingjunxue.proxyidentity.platform.bukkit;

public interface ProtocolLibInjectionStrategy {
    void inject() throws ReflectiveOperationException;

    void uninject() throws ReflectiveOperationException;
}
