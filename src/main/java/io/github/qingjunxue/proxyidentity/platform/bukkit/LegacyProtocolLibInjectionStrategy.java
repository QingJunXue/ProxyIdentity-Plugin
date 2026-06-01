package io.github.qingjunxue.proxyidentity.platform.bukkit;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.injector.netty.ChannelListener;
import com.comphenix.protocol.injector.netty.InjectionFactory;
import com.comphenix.protocol.injector.netty.Injector;
import com.comphenix.protocol.injector.netty.ProtocolInjector;
import com.comphenix.protocol.injector.server.TemporaryPlayerFactory;
import com.comphenix.protocol.reflect.FuzzyReflection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.github.qingjunxue.proxyidentity.ProxyProtocolSwitchHandler;
import io.github.qingjunxue.proxyidentity.ReflectiveAccess;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.NoSuchElementException;
import java.util.logging.Logger;

public class LegacyProtocolLibInjectionStrategy implements ProtocolLibInjectionStrategy {
    private final Logger logger;

    private Field injectorFactoryField;
    private ProtocolInjector injector;
    private InjectionFactory oldFactory;

    public LegacyProtocolLibInjectionStrategy(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void inject() throws ReflectiveOperationException {
        try {
            this.uninject();
        } catch (Throwable ignored) {
        }

        ProtocolManager pm = ProtocolLibrary.getProtocolManager();
        Field injectorField = FuzzyReflection.fromObject(pm, true)
                .getFieldByType("nettyInjector", ProtocolInjector.class);
        injectorField.setAccessible(true);
        injector = (ProtocolInjector) injectorField.get(pm);

        injectorFactoryField = FuzzyReflection.fromObject(injector, true)
                .getFieldByType("factory", InjectionFactory.class);
        injectorFactoryField.setAccessible(true);

        oldFactory = (InjectionFactory) injectorFactoryField.get(injector);
        InjectionFactory newFactory = new HAProxyInjectorFactory(oldFactory.getPlugin());
        ReflectiveAccess.copyState(InjectionFactory.class, oldFactory, newFactory);
        injectorFactoryField.set(injector, newFactory);
    }

    @Override
    public void uninject() throws ReflectiveOperationException {
        if (injectorFactoryField != null && injector != null && oldFactory != null) {
            InjectionFactory currentFactory = (InjectionFactory) injectorFactoryField.get(injector);
            ReflectiveAccess.copyState(InjectionFactory.class, currentFactory, oldFactory);
            injectorFactoryField.set(injector, oldFactory);
            oldFactory = null;
        }
    }

    class HAProxyInjectorFactory extends InjectionFactory {
        public HAProxyInjectorFactory(Plugin plugin) {
            super(plugin);
        }

        @Override
        public @NotNull Injector fromChannel(Channel channel,
                                             ChannelListener listener,
                                             TemporaryPlayerFactory playerFactory) {
            ChannelPipeline pipeline = channel.pipeline();
            if (channel.isOpen() && pipeline.get("proxy-identity") == null) {
                ChannelHandler networkManager = BukkitPlugin.getNetworkManager(pipeline);
                inject(pipeline, networkManager);
            }

            return super.fromChannel(channel, listener, playerFactory);
        }

        private void inject(ChannelPipeline pipeline, ChannelHandler networkManager) {
            synchronized (networkManager) {
                ProxyProtocolSwitchHandler detectorHandler = new ProxyProtocolSwitchHandler(logger,
                        new BukkitProxyAddressHandler(networkManager));
                try {
                    pipeline.addAfter("timeout", "proxy-identity", detectorHandler);
                } catch (NoSuchElementException e) {
                    pipeline.addFirst("proxy-identity", detectorHandler);
                }
            }
        }
    }
}