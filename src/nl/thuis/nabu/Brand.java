// SPDX-License-Identifier: Apache-2.0
package nl.thuis.nabu;

/** What makes this build Nabu: the overlay key, the name and the light colours. */
final class Brand {
    private Brand() {}

    /** The overlay this skin shows. */
    static final String KEY = "nabu";
    /** The name over the turn on a full screen. */
    static final String NAME = "Nabu";

    /** The eyes and lights for a Light colour choice; Mint unless another is picked. */
    static int light(Object choice) {
        if ("Cyan".equals(choice)) return 0xFF4FD8F0;
        if ("Blue".equals(choice)) return 0xFF5AA9FF;
        if ("Violet".equals(choice)) return 0xFFB28CFF;
        if ("Amber".equals(choice)) return 0xFFFFC266;
        if ("Pink".equals(choice)) return 0xFFFF8FC7;
        return 0xFF7FF0D8;
    }
}
