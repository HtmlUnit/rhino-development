/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.tests;

import org.junit.Test;
import org.mozilla.javascript.Undefined;

/**
 * Test for delete that should apply for properties defined in prototype chain. See
 * https://bugzilla.mozilla.org/show_bug.cgi?id=510504
 *
 * @author Marc Guillemot
 */
public class DeletePropertyTest {

    @Test
    /** delete should not delete anything in the prototype chain. */
    public void deletePropInPrototype() throws Exception {
        final String script =
                "Array.prototype.foo = function() {};\n"
                        + "Array.prototype[1] = function() {};\n"
                        + "var t = [];\n"
                        + "[].foo();\n"
                        + "for (var i in t) delete t[i];\n"
                        + "[].foo();\n"
                        + "[][1]();\n";

        Utils.assertWithAllModes(Undefined.instance, script);
    }
}
