package com.example.oops.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.*;

class MediaControllerResourceTest {
    @Test void successfulRangeTransfersStreamOwnershipToTheCaller() throws Exception {
        var source = new TrackingStream();
        try (var range = region(source, 2, 3).getInputStream()) {
            assertThat(source.closes).isZero();
            assertThat(range.readAllBytes()).containsExactly(2, 3, 4);
        }
        assertThat(source.closes).isOne();
    }

    @Test void skipFailureClosesSourceAndPreservesTheOriginalException() throws Exception {
        var source = new TrackingStream();
        source.skipFailure = new IOException("skip failed");
        source.closeFailure = new IOException("close failed");
        var resource = region(source, 1, 1);
        assertThatThrownBy(resource::getInputStream).isSameAs(source.skipFailure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(source.closeFailure));
        assertThat(source.closes).isOne();
    }

    @Test void runtimeSkipFailureAlsoClosesSource() throws Exception {
        var source = new TrackingStream();
        source.runtimeFailure = new IllegalStateException("skip failed");
        var resource = region(source, 1, 1);
        assertThatThrownBy(resource::getInputStream).isSameAs(source.runtimeFailure);
        assertThat(source.closes).isOne();
    }

    @Test void skipWithoutProgressStillStartsAtTheRequestedOffset() throws Exception {
        var source = new TrackingStream();
        source.skipReturnsZero = true;
        try (var range = region(source, 2, 2).getInputStream()) {
            assertThat(range.readAllBytes()).containsExactly(2, 3);
        }
        assertThat(source.closes).isOne();
    }

    @Test void earlyEofClosesSourceInsteadOfReturningAnIncorrectRange() throws Exception {
        var source = new TrackingStream();
        var resource = region(source, 20, 1);
        assertThatThrownBy(resource::getInputStream).isInstanceOf(EOFException.class);
        assertThat(source.closes).isOne();
    }

    @Test void repeatedFailuresDoNotLeaveOpenSources() throws Exception {
        for (int i = 0; i < 500; i++) {
            var source = new TrackingStream();
            source.skipFailure = new IOException("injected failure");
            var resource = region(source, 1, 1);
            assertThatThrownBy(resource::getInputStream).isSameAs(source.skipFailure);
            assertThat(source.closes).isOne();
        }
    }

    private Resource region(InputStream stream, long position, long count) throws Exception {
        Resource source = new AbstractResource() {
            @Override public String getDescription() { return "offline range lifecycle fixture"; }
            @Override public InputStream getInputStream() { return stream; }
        };
        var type = Class.forName(MediaController.class.getName() + "$ResourceRegionResource");
        var constructor = type.getDeclaredConstructor(ResourceRegion.class);
        constructor.setAccessible(true);
        return (Resource) constructor.newInstance(new ResourceRegion(source, position, count));
    }

    private static class TrackingStream extends InputStream {
        private final ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[]{0, 1, 2, 3, 4});
        int closes;
        IOException skipFailure, closeFailure;
        RuntimeException runtimeFailure;
        boolean skipReturnsZero;
        @Override public int read() { return bytes.read(); }
        @Override public long skip(long count) throws IOException {
            if (skipFailure != null) throw skipFailure;
            if (runtimeFailure != null) throw runtimeFailure;
            return skipReturnsZero ? 0 : bytes.skip(count);
        }
        @Override public void close() throws IOException {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }
}
