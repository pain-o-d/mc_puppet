package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the tools still speak the protocol the mod answers in")
class ProtocolToolsTest {

    /** The tests run from common/ or from mc1.20.1/common/: walk up to the one with tools/puppet/lib.js. */
    private static Path lib() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("tools").resolve("puppet").resolve("lib.js");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("tools/puppet/lib.js not found above " + Path.of("").toAbsolutePath());
    }

    @Test
    void protocolVersionIsAmongThoseTheToolsSpeak() throws Exception {
        String source = Files.readString(lib(), StandardCharsets.UTF_8);
        Matcher list = Pattern.compile("const\\s+PROTOCOLS\\s*=\\s*\\[([^\\]]*)\\]").matcher(source);
        assertTrue(list.find(), "no \"const PROTOCOLS = [...]\" in tools/puppet/lib.js");
        boolean spoken = false;
        for (String item : list.group(1).split(",")) {
            if (!item.isBlank() && Integer.parseInt(item.trim()) == Protocol.VERSION) {
                spoken = true;
            }
        }
        assertTrue(spoken, "Protocol.VERSION " + Protocol.VERSION + " is not in PROTOCOLS [" + list.group(1).trim()
                + "] in tools/puppet/lib.js: add it, as long as the tools still speak the others");
    }
}
