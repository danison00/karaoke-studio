package com.karaokestudio.youtube;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

public class UpstreamHostPolicy {

    private final HostResolver hostResolver;

    public UpstreamHostPolicy() {
        this(host -> List.of(InetAddress.getAllByName(host)));
    }

    public UpstreamHostPolicy(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    public void assertAllowed(URI uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new UpstreamHostPolicyException("O destino upstream deve ser HTTPS sem credenciais e na porta padrão");
        }

        var host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!isAllowedHost(host)) {
            throw new UpstreamHostPolicyException("O host upstream não é permitido");
        }

        final List<InetAddress> addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (Exception exception) {
            throw new UpstreamHostPolicyException("Não foi possível validar o host upstream");
        }
        if (addresses == null || addresses.isEmpty() || addresses.stream().anyMatch(UpstreamHostPolicy::isPrivateAddress)) {
            throw new UpstreamHostPolicyException("O destino upstream resolve para uma rede privada ou local");
        }
    }

    private static boolean isAllowedHost(String host) {
        return host.equals("googlevideo.com")
                || host.endsWith(".googlevideo.com")
                || host.equals("youtube.com")
                || host.endsWith(".youtube.com");
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            var bytes = ipv4.getAddress();
            var first = bytes[0] & 0xff;
            var second = bytes[1] & 0xff;
            return first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254
                    || first == 198 && second >= 18 && second <= 19;
        }
        if (address instanceof Inet6Address ipv6) {
            var bytes = ipv6.getAddress();
            return (bytes[0] & 0xfe) == 0xfc || (bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0
                    && bytes[3] == 0 && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0);
        }
        return false;
    }

    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws Exception;
    }
}
