/*
 * Copyright (c) 2004, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 5014447
 * @summary Test deserialization of UUID
 * @key randomness
 */

package test.java.util.UUID;

import java.io.*;
import java.util.*;

/**
 * This class tests to see if UUID can be serialized and
 * deserialized properly. This originally failed because
 * the transient fields which were computed on demand are
 * not set back to the uninitialized value upon reconstitition.
 */
public class Serial {

    // Android-added: method added so the runner can run the test.
    @org.testng.annotations.Test
    public static void runTests() throws Exception {
        main(null);
    }

    public static void main(String[] args) throws Exception {
        UUID a = UUID.randomUUID();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(a);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        UUID b = (UUID)ois.readObject();
        if (!a.equals(b))
            throw new RuntimeException("UUIDs not equal");
        oos.close();
        ois.close();
    }
}
