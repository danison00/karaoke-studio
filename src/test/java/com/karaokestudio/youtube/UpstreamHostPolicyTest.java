package com.karaokestudio.youtube;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class UpstreamHostPolicyTest {

    @Test
    void allowsHttpsGoogleVideoHostWithPublicAddress() throws Exception {
        var policy = policy(InetAddress.getByName("142.250.72.14"));

        assertThatCode(() -> policy.assertAllowed(URI.create("https://rr1---sn.googlevideo.com/videoplayback")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnapprovedHostEvenWhenItResolvesPublicly() throws Exception {
        var policy = policy(InetAddress.getByName("142.250.72.14"));

        assertThatThrownBy(() -> policy.assertAllowed(URI.create("https://evil.example/video.mp4")))
                .isInstanceOf(UpstreamHostPolicyException.class);
    }

    @Test
    void rejectsPrivateLoopbackAndLinkLocalDestinations() throws Exception {
        var loopback = policy(InetAddress.getByName("127.0.0.1"));
        var privateNetwork = policy(InetAddress.getByName("10.10.10.10"));
        var linkLocal = policy(InetAddress.getByName("169.254.169.254"));

        assertThatThrownBy(() -> loopback.assertAllowed(URI.create("https://googlevideo.com/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
        assertThatThrownBy(() -> privateNetwork.assertAllowed(URI.create("https://googlevideo.com/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
        assertThatThrownBy(() -> linkLocal.assertAllowed(URI.create("https://googlevideo.com/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
    }

    @Test
    void rejectsHttpCredentialsAndNonDefaultPort() throws Exception {
        var policy = policy(InetAddress.getByName("142.250.72.14"));

        assertThatThrownBy(() -> policy.assertAllowed(URI.create("http://googlevideo.com/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
        assertThatThrownBy(() -> policy.assertAllowed(URI.create("https://user:pass@googlevideo.com/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
        assertThatThrownBy(() -> policy.assertAllowed(URI.create("https://googlevideo.com:8443/a")))
                .isInstanceOf(UpstreamHostPolicyException.class);
    }

    private static UpstreamHostPolicy policy(InetAddress... addresses) {
        return new UpstreamHostPolicy(host -> List.of(addresses));
    }
}
