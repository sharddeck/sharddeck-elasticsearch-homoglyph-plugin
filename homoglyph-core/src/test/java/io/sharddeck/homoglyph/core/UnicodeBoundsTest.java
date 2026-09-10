// SPDX-License-Identifier: MPL-2.0
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.sharddeck.homoglyph.core;

import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class UnicodeBoundsTest {
    @Test void pinnedIcuAllocationFitsConservativePreflightOnAdversarialInputs() {
        assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        UnicodeData data=UnicodeData.INSTANCE;
        List<String> inputs=new ArrayList<>(List.of("héllo😀", "m".repeat(4096), "\uFDFA".repeat(512),
            "\u200b".repeat(4096), "\u0344".repeat(4096), "\u1F82".repeat(1024),
            "a"+"\u0315\u0300".repeat(2047), "α"+"\u0315\u0300".repeat(2047),
            "\uD834\uDD1E".repeat(2048), "\uD835\uDC00".repeat(2048)));
        Random random=new Random(1700781);
        for(int i=0;i<100;i++) {
            StringBuilder value=new StringBuilder();
            for(int j=0;j<200;j++) {
                int cp=random.nextInt(0x110000);
                if(cp<0xD800 || cp>0xDFFF) value.appendCodePoint(cp);
            }
            inputs.add(value.toString());
        }
        for(String input:inputs) {
            char[] chars=input.toCharArray(); long bound=data.reservation(chars,0,chars.length);
            for(int i=0;i<5;i++) data.key(input); // Exclude lazy shared ICU data from token scratch.
            long before=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            String result=data.key(new String(chars)); result.toCharArray();
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
            assertTrue(allocated<=bound, "allocated="+allocated+", reserved="+bound+", input units="+input.length());
        }
    }
}
