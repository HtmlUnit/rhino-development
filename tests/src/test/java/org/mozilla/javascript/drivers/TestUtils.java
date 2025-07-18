/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.drivers;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.mozilla.javascript.ContextFactory;

public class TestUtils {
    private static ContextFactory.GlobalSetter globalSetter;

    public static void grabContextFactoryGlobalSetter() {
        if (globalSetter == null) {
            globalSetter = ContextFactory.getGlobalSetter();
        }
    }

    public static void setGlobalContextFactory(ContextFactory factory) {
        grabContextFactoryGlobalSetter();
        globalSetter.setContextFactoryGlobal(factory);
    }

    public static File[] recursiveListFiles(File dir, FileFilter filter) {
        if (!dir.isDirectory()) throw new IllegalArgumentException(dir + " is not a directory");
        List<File> fileList = new ArrayList<File>();
        recursiveListFilesHelper(dir, filter, fileList);
        return fileList.toArray(new File[fileList.size()]);
    }

    public static void recursiveListFilesHelper(File dir, FileFilter filter, List<File> fileList) {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                recursiveListFilesHelper(f, filter, fileList);
            } else {
                if (filter.accept(f)) fileList.add(f);
            }
        }
    }

    public static void addTestsFromFile(String filename, List<String> list) throws IOException {
        addTestsFromStream(new FileInputStream(new File(filename)), list);
    }

    private static void addTestsFromStream(InputStream in, List<String> list) throws IOException {
        Properties props = new Properties();
        props.load(in);
        for (Object obj : props.keySet()) {
            list.add(obj.toString());
        }
    }

    public static String[] loadTestsFromResource(String resource) throws IOException {
        InputStream in = JsTestsBase.class.getResourceAsStream(resource);
        if (in == null) {
            return new String[0];
        }

        List<String> list = new ArrayList<String>();
        addTestsFromStream(in, list);
        return list.toArray(new String[0]);
    }

    public static boolean matches(String[] patterns, String path) {
        for (int i = 0; i < patterns.length; i++) {
            if (path.startsWith(patterns[i])) {
                return true;
            }
        }
        return false;
    }

    public static final FileFilter JS_FILE_FILTER =
            new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    String path = pathname.getAbsolutePath();
                    return path.endsWith(".js") && !path.endsWith("_FIXTURE.js");
                }
            };
}
