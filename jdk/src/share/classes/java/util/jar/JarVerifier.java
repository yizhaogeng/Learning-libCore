/*
 * Copyright (c) 1997, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package java.util.jar;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.zip.ZipEntry;

import sun.security.util.ManifestDigester;
import sun.security.util.ManifestEntryVerifier;
import sun.security.util.SignatureFileVerifier;
import sun.security.util.Debug;

/**
 *
 * @author      Roland Schemers
 */
class JarVerifier {

    /* Are we debugging ? */
    static final Debug debug = Debug.getInstance("jar");

    /* a table mapping names to code signers, for jar entries that have
       had their actual hashes verified */
    private Hashtable verifiedSigners;

    /* a table mapping names to code signers, for jar entries that have
       passed the .SF/.DSA/.EC -> MANIFEST check */
    private Hashtable sigFileSigners;

    /* a hash table to hold .SF bytes */
    private Hashtable sigFileData;

    /** "queue" of pending PKCS7 blocks that we couldn't parse
     *  until we parsed the .SF file */
    private ArrayList pendingBlocks;

    /* cache of CodeSigner objects */
    private ArrayList signerCache;

    /* Are we parsing a block? */
    private boolean parsingBlockOrSF = false;

    /* Are we done parsing META-INF entries? */
    private boolean parsingMeta = true;

    /* Are there are files to verify? */
    private boolean anyToVerify = true;

    /* The output stream to use when keeping track of files we are interested
       in */
    private ByteArrayOutputStream baos;

    /** The ManifestDigester object */
    private volatile ManifestDigester manDig;

    /** the bytes for the manDig object */
    byte manifestRawBytes[] = null;

    /** controls eager signature validation */
    boolean eagerValidation;

    /** makes code source singleton instances unique to us */
    private Object csdomain = new Object();

    /** collect -DIGEST-MANIFEST values for blacklist */
    private List manifestDigests;

    public JarVerifier(byte rawBytes[]) {
        manifestRawBytes = rawBytes;
        sigFileSigners = new Hashtable();
        verifiedSigners = new Hashtable();
        sigFileData = new Hashtable(11);
        pendingBlocks = new ArrayList();
        baos = new ByteArrayOutputStream();
        manifestDigests = new ArrayList();
    }

    /**
     * This method scans to see which entry we're parsing and
     * keeps various state information depending on what type of
     * file is being parsed.
     */
    public void beginEntry(JarEntry je, ManifestEntryVerifier mev)
        throws IOException
    {
        if (je == null)
            return;

        if (debug != null) {
            debug.println("beginEntry "+je.getName());
        }

        String name = je.getName();

        /*
         * Assumptions:
         * 1. The manifest should be the first entry in the META-INF directory.
         * 2. The .SF/.DSA/.EC files follow the manifest, before any normal entries
         * 3. Any of the following will throw a SecurityException:
         *    a. digest mismatch between a manifest section and
         *       the SF section.
         *    b. digest mismatch between the actual jar entry and the manifest
         */

        if (parsingMeta) {
            String uname = name.toUpperCase(Locale.ENGLISH);
            if ((uname.startsWith("META-INF/") ||
                 uname.startsWith("/META-INF/"))) {

                if (je.isDirectory()) {
                    mev.setEntry(null, je);
                    return;
                }

                if (SignatureFileVerifier.isBlockOrSF(uname)) {
                    /* We parse only DSA, RSA or EC PKCS7 blocks. */
                    parsingBlockOrSF = true;
                    baos.reset();
                    mev.setEntry(null, je);
                }
                return;
            }
        }

        if (parsingMeta) {
            doneWithMeta();
        }

        if (je.isDirectory()) {
            mev.setEntry(null, je);
            return;
        }

        // be liberal in what you accept. If the name starts with ./, remove
        // it as we internally canonicalize it with out the ./.
        if (name.startsWith("./"))
            name = name.substring(2);

        // be liberal in what you accept. If the name starts with /, remove
        // it as we internally canonicalize it with out the /.
        if (name.startsWith("/"))
            name = name.substring(1);

        // only set the jev object for entries that have a signature
        // (either verified or not)
        if (sigFileSigners.get(name) != null ||
                verifiedSigners.get(name) != null) {
            mev.setEntry(name, je);
            return;
        }

        // don't compute the digest for this entry
        mev.setEntry(null, je);

        return;
    }

    /**
     * update a single byte.
     */

    public void update(int b, ManifestEntryVerifier mev)
        throws IOException
    {
        if (b != -1) {
            if (parsingBlockOrSF) {
                baos.write(b);
            } else {
                mev.update((byte)b);
            }
        } else {
            processEntry(mev);
        }
    }

    /**
     * update an array of bytes.
     */

    public void update(int n, byte[] b, int off, int len,
                       ManifestEntryVerifier mev)
        throws IOException
    {
        if (n != -1) {
            if (parsingBlockOrSF) {
                baos.write(b, off, n);
            } else {
                mev.update(b, off, n);
            }
        } else {
            processEntry(mev);
        }
    }

    /**
     * called when we reach the end of entry in one of the read() methods.
     */
    private void processEntry(ManifestEntryVerifier mev)
        throws IOException
    {
        if (!parsingBlockOrSF) {
            JarEntry je = mev.getEntry();
            if ((je != null) && (je.signers == null)) {
                je.signers = mev.verify(verifiedSigners, sigFileSigners);
                je.certs = mapSignersToCertArray(je.signers);
            }
        } else {

            try {
                parsingBlockOrSF = false;

                if (debug != null) {
                    debug.println("processEntry: processing block");
                }

                String uname = mev.getEntry().getName()
                                             .toUpperCase(Locale.ENGLISH);

                if (uname.endsWith(".SF")) {
                    String key = uname.substring(0, uname.length()-3);
                    byte bytes[] = baos.toByteArray();
                    // add to sigFileData in case future blocks need it
                    sigFileData.put(key, bytes);
                    // check pending blocks, we can now process
                    // anyone waiting for this .SF file
                    Iterator it = pendingBlocks.iterator();
                    while (it.hasNext()) {
                        SignatureFileVerifier sfv =
                            (SignatureFileVerifier) it.next();
                        if (sfv.needSignatureFile(key)) {
                            if (debug != null) {
                                debug.println(
                                 "processEntry: processing pending block");
                            }

                            sfv.setSignatureFile(bytes);
                            sfv.process(sigFileSigners, manifestDigests);
                        }
                    }
                    return;
                }

                // now we are parsing a signature block file

                String key = uname.substring(0, uname.lastIndexOf("."));

                if (signerCache == null)
                    signerCache = new ArrayList();

                if (manDig == null) {
                    synchronized(manifestRawBytes) {
                        if (manDig == null) {
                            manDig = new ManifestDigester(manifestRawBytes);
                            manifestRawBytes = null;
                        }
                    }
                }

                SignatureFileVerifier sfv =
                  new SignatureFileVerifier(signerCache,
                                            manDig, uname, baos.toByteArray());

                if (sfv.needSignatureFileBytes()) {
                    // see if we have already parsed an external .SF file
                    byte[] bytes = (byte[]) sigFileData.get(key);

                    if (bytes == null) {
                        // put this block on queue for later processing
                        // since we don't have the .SF bytes yet
                        // (uname, block);
                        if (debug != null) {
                            debug.println("adding pending block");
                        }
                        pendingBlocks.add(sfv);
                        return;
                    } else {
                        sfv.setSignatureFile(bytes);
                    }
                }
                sfv.process(sigFileSigners, manifestDigests);

            } catch (IOException ioe) {
                // e.g. sun.security.pkcs.ParsingException
                if (debug != null) debug.println("processEntry caught: "+ioe);
                // ignore and treat as unsigned
            } catch (SignatureException se) {
                if (debug != null) debug.println("processEntry caught: "+se);
                // ignore and treat as unsigned
            } catch (NoSuchAlgorithmException nsae) {
                if (debug != null) debug.println("processEntry caught: "+nsae);
                // ignore and treat as unsigned
            } catch (CertificateException ce) {
                if (debug != null) debug.println("processEntry caught: "+ce);
                // ignore and treat as unsigned
            }
        }
    }

    /**
     * Return an array of java.security.cert.Certificate objects for
     * the given file in the jar.
     * @deprecated
     */
    public java.security.cert.Certificate[] getCerts(String name)
    {
        return mapSignersToCertArray(getCodeSigners(name));
    }

    public java.security.cert.Certificate[] getCerts(JarFile jar, JarEntry entry)
    {
        return mapSignersToCertArray(getCodeSigners(jar, entry));
    }

    /**
     * return an array of CodeSigner objects for
     * the given file in the jar. this array is not cloned.
     *
     */
    public CodeSigner[] getCodeSigners(String name)
    {
        return (CodeSigner[])verifiedSigners.get(name);
    }

    public CodeSigner[] getCodeSigners(JarFile jar, JarEntry entry)
    {
        String name = entry.getName();
        if (eagerValidation && sigFileSigners.get(name) != null) {
            /*
             * Force a read of the entry data to generate the
             * verification hash.
             */
            try {
                InputStream s = jar.getInputStream(entry);
                byte[] buffer = new byte[1024];
                int n = buffer.length;
                while (n != -1) {
                    n = s.read(buffer, 0, buffer.length);
                }
                s.close();
            } catch (IOException e) {
            }
        }
        return getCodeSigners(name);
    }

    /*
     * Convert an array of signers into an array of concatenated certificate
     * arrays.
     */
    private static java.security.cert.Certificate[] mapSignersToCertArray(
        CodeSigner[] signers) {

        if (signers != null) {
            ArrayList certChains = new ArrayList();
            for (int i = 0; i < signers.length; i++) {
                certChains.addAll(
                    signers[i].getSignerCertPath().getCertificates());
            }

            // Convert into a Certificate[]
            return (java.security.cert.Certificate[])
                certChains.toArray(
                    new java.security.cert.Certificate[certChains.size()]);
        }
        return null;
    }

    /**
     * returns true if there no files to verify.
     * should only be called after all the META-INF entries
     * have been processed.
     */
    boolean nothingToVerify()
    {
        return (anyToVerify == false);
    }

    /**
     * called to let us know we have processed all the
     * META-INF entries, and if we re-read one of them, don't
     * re-process it. Also gets rid of any data structures
     * we needed when parsing META-INF entries.
     */
    void doneWithMeta()
    {
        parsingMeta = false;
        anyToVerify = !sigFileSigners.isEmpty();
        baos = null;
        sigFileData = null;
        pendingBlocks = null;
        signerCache = null;
        manDig = null;
        // MANIFEST.MF is always treated as signed and verified,
        // move its signers from sigFileSigners to verifiedSigners.
        if (sigFileSigners.containsKey(JarFile.MANIFEST_NAME)) {
            verifiedSigners.put(JarFile.MANIFEST_NAME,
                    sigFileSigners.remove(JarFile.MANIFEST_NAME));
        }
    }

    static class VerifierStream extends java.io.InputStream {

        private InputStream is;
        private JarVerifier jv;
        private ManifestEntryVerifier mev;
        private long numLeft;

        VerifierStream(Manifest man,
                       JarEntry je,
                       InputStream is,
                       JarVerifier jv) throws IOException
        {
            this.is = is;
            this.jv = jv;
            this.mev = new ManifestEntryVerifier(man);
            this.jv.beginEntry(je, mev);
            this.numLeft = je.getSize();
            if (this.numLeft == 0)
                this.jv.update(-1, this.mev);
        }

        public int read() throws IOException
        {
            if (numLeft > 0) {
                int b = is.read();
                jv.update(b, mev);
                numLeft--;
                if (numLeft == 0)
                    jv.update(-1, mev);
                return b;
            } else {
                return -1;
            }
        }

        public int read(byte b[], int off, int len) throws IOException {
            if ((numLeft > 0) && (numLeft < len)) {
                len = (int)numLeft;
            }

            if (numLeft > 0) {
                int n = is.read(b, off, len);
                jv.update(n, b, off, len, mev);
                numLeft -= n;
                if (numLeft == 0)
                    jv.update(-1, b, off, len, mev);
                return n;
            } else {
                return -1;
            }
        }

        public void close()
            throws IOException
        {
            if (is != null)
                is.close();
            is = null;
            mev = null;
            jv = null;
        }

        public int available() throws IOException {
            return is.available();
        }

    }

    // Extended JavaUtilJarAccess CodeSource API Support

    private Map urlToCodeSourceMap = new HashMap();
    private Map signerToCodeSource = new HashMap();
    private URL lastURL;
    private Map lastURLMap;

    /*
     * Create a unique mapping from codeSigner cache entries to CodeSource.
     * In theory, multiple URLs origins could map to a single locally cached
     * and shared JAR file although in practice there will be a single URL in use.
     */
    private synchronized CodeSource mapSignersToCodeSource(URL url, CodeSigner[] signers) {
        Map map;
        if (url == lastURL) {
            map = lastURLMap;
        } else {
            map = (Map) urlToCodeSourceMap.get(url);
            if (map == null) {
                map = new HashMap();
                urlToCodeSourceMap.put(url, map);
            }
            lastURLMap = map;
            lastURL = url;
        }
        CodeSource cs = (CodeSource) map.get(signers);
        if (cs == null) {
            cs = new VerifierCodeSource(csdomain, url, signers);
            signerToCodeSource.put(signers, cs);
        }
        return cs;
    }

    private CodeSource[] mapSignersToCodeSources(URL url, List signers, boolean unsigned) {
        List sources = new ArrayList();

        for (int i = 0; i < signers.size(); i++) {
            sources.add(mapSignersToCodeSource(url, (CodeSigner[]) signers.get(i)));
        }
        if (unsigned) {
            sources.add(mapSignersToCodeSource(url, null));
        }
        return (CodeSource[]) sources.toArray(new CodeSource[sources.size()]);
    }
    private CodeSigner[] emptySigner = new CodeSigner[0];

    /*
     * Match CodeSource to a CodeSigner[] in the signer cache.
     */
    private CodeSigner[] findMatchingSigners(CodeSource cs) {
        if (cs instanceof VerifierCodeSource) {
            VerifierCodeSource vcs = (VerifierCodeSource) cs;
            if (vcs.isSameDomain(csdomain)) {
                return ((VerifierCodeSource) cs).getPrivateSigners();
            }
        }

        /*
         * In practice signers should always be optimized above
         * but this handles a CodeSource of any type, just in case.
         */
        CodeSource[] sources = mapSignersToCodeSources(cs.getLocation(), getJarCodeSigners(), true);
        List sourceList = new ArrayList();
        for (int i = 0; i < sources.length; i++) {
            sourceList.add(sources[i]);
        }
        int j = sourceList.indexOf(cs);
        if (j != -1) {
            CodeSigner[] match;
            match = ((VerifierCodeSource) sourceList.get(j)).getPrivateSigners();
            if (match == null) {
                match = emptySigner;
            }
            return match;
        }
        return null;
    }

    /*
     * Instances of this class hold uncopied references to internal
     * signing data that can be compared by object reference identity.
     */
    private static class VerifierCodeSource extends CodeSource {

        URL vlocation;
        CodeSigner[] vsigners;
        java.security.cert.Certificate[] vcerts;
        Object csdomain;

        VerifierCodeSource(Object csdomain, URL location, CodeSigner[] signers) {
            super(location, signers);
            this.csdomain = csdomain;
            vlocation = location;
            vsigners = signers; // from signerCache
        }

        VerifierCodeSource(Object csdomain, URL location, java.security.cert.Certificate[] certs) {
            super(location, certs);
            this.csdomain = csdomain;
            vlocation = location;
            vcerts = certs; // from signerCache
        }

        /*
         * All VerifierCodeSource instances are constructed based on
         * singleton signerCache or signerCacheCert entries for each unique signer.
         * No CodeSigner<->Certificate[] conversion is required.
         * We use these assumptions to optimize equality comparisons.
         */
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj instanceof VerifierCodeSource) {
                VerifierCodeSource that = (VerifierCodeSource) obj;

                /*
                 * Only compare against other per-signer singletons constructed
                 * on behalf of the same JarFile instance. Otherwise, compare
                 * things the slower way.
                 */
                if (isSameDomain(that.csdomain)) {
                    if (that.vsigners != this.vsigners
                            || that.vcerts != this.vcerts) {
                        return false;
                    }
                    if (that.vlocation != null) {
                        return that.vlocation.equals(this.vlocation);
                    } else if (this.vlocation != null) {
                        return this.vlocation.equals(that.vlocation);
                    } else { // both null
                        return true;
                    }
                }
            }
            return super.equals(obj);
        }

        boolean isSameDomain(Object csdomain) {
            return this.csdomain == csdomain;
        }

        private CodeSigner[] getPrivateSigners() {
            return vsigners;
        }

        private java.security.cert.Certificate[] getPrivateCertificates() {
            return vcerts;
        }
    }
    private Map signerMap;

    private synchronized Map signerMap() {
        if (signerMap == null) {
            /*
             * Snapshot signer state so it doesn't change on us. We care
             * only about the asserted signatures. Verification of
             * signature validity happens via the JarEntry apis.
             */
            signerMap = new HashMap(verifiedSigners.size() + sigFileSigners.size());
            signerMap.putAll(verifiedSigners);
            signerMap.putAll(sigFileSigners);
        }
        return signerMap;
    }

    public synchronized Enumeration<String> entryNames(JarFile jar, final CodeSource[] cs) {
        final Map map = signerMap();
        final Iterator itor = map.entrySet().iterator();
        boolean matchUnsigned = false;

        /*
         * Grab a single copy of the CodeSigner arrays. Check
         * to see if we can optimize CodeSigner equality test.
         */
        List req = new ArrayList(cs.length);
        for (int i = 0; i < cs.length; i++) {
            CodeSigner[] match = findMatchingSigners(cs[i]);
            if (match != null) {
                if (match.length > 0) {
                    req.add(match);
                } else {
                    matchUnsigned = true;
                }
            }
        }

        final List signersReq = req;
        final Enumeration enum2 = (matchUnsigned) ? unsignedEntryNames(jar) : emptyEnumeration;

        return new Enumeration<String>() {

            String name;

            public boolean hasMoreElements() {
                if (name != null) {
                    return true;
                }

                while (itor.hasNext()) {
                    Map.Entry e = (Map.Entry) itor.next();
                    if (signersReq.contains((CodeSigner[]) e.getValue())) {
                        name = (String) e.getKey();
                        return true;
                    }
                }
                while (enum2.hasMoreElements()) {
                    name = (String) enum2.nextElement();
                    return true;
                }
                return false;
            }

            public String nextElement() {
                if (hasMoreElements()) {
                    String value = name;
                    name = null;
                    return value;
                }
                throw new NoSuchElementException();
            }
        };
    }

    /*
     * Like entries() but screens out internal JAR mechanism entries
     * and includes signed entries with no ZIP data.
     */
    public Enumeration<JarEntry> entries2(final JarFile jar, Enumeration e) {
        final Map map = new HashMap();
        map.putAll(signerMap());
        final Enumeration enum_ = e;
        return new Enumeration<JarEntry>() {

            Enumeration signers = null;
            JarEntry entry;

            public boolean hasMoreElements() {
                if (entry != null) {
                    return true;
                }
                while (enum_.hasMoreElements()) {
                    ZipEntry ze = (ZipEntry) enum_.nextElement();
                    if (JarVerifier.isSigningRelated(ze.getName())) {
                        continue;
                    }
                    entry = jar.newEntry(ze);
                    return true;
                }
                if (signers == null) {
                    signers = Collections.enumeration(map.keySet());
                }
                while (signers.hasMoreElements()) {
                    String name = (String) signers.nextElement();
                    entry = jar.newEntry(new ZipEntry(name));
                    return true;
                }

                // Any map entries left?
                return false;
            }

            public JarEntry nextElement() {
                if (hasMoreElements()) {
                    JarEntry je = entry;
                    map.remove(je.getName());
                    entry = null;
                    return je;
                }
                throw new NoSuchElementException();
            }
        };
    }
    private Enumeration emptyEnumeration = new Enumeration<String>() {

        public boolean hasMoreElements() {
            return false;
        }

        public String nextElement() {
            throw new NoSuchElementException();
        }
    };

    // true if file is part of the signature mechanism itself
    static boolean isSigningRelated(String name) {
        name = name.toUpperCase(Locale.ENGLISH);
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        name = name.substring(9);
        if (name.indexOf('/') != -1) {
            return false;
        }
        if (name.endsWith(".DSA")
                || name.endsWith(".RSA")
                || name.endsWith(".SF")
                || name.endsWith(".EC")
                || name.startsWith("SIG-")
                || name.equals("MANIFEST.MF")) {
            return true;
        }
        return false;
    }

    private Enumeration<String> unsignedEntryNames(JarFile jar) {
        final Map map = signerMap();
        final Enumeration entries = jar.entries();
        return new Enumeration<String>() {

            String name;

            /*
             * Grab entries from ZIP directory but screen out
             * metadata.
             */
            public boolean hasMoreElements() {
                if (name != null) {
                    return true;
                }
                while (entries.hasMoreElements()) {
                    String value;
                    ZipEntry e = (ZipEntry) entries.nextElement();
                    value = e.getName();
                    if (e.isDirectory() || isSigningRelated(value)) {
                        continue;
                    }
                    if (map.get(value) == null) {
                        name = value;
                        return true;
                    }
                }
                return false;
            }

            public String nextElement() {
                if (hasMoreElements()) {
                    String value = name;
                    name = null;
                    return value;
                }
                throw new NoSuchElementException();
            }
        };
    }
    private List jarCodeSigners;

    private synchronized List getJarCodeSigners() {
        CodeSigner[] signers;
        if (jarCodeSigners == null) {
            HashSet set = new HashSet();
            set.addAll(signerMap().values());
            jarCodeSigners = new ArrayList();
            jarCodeSigners.addAll(set);
        }
        return jarCodeSigners;
    }

    public synchronized CodeSource[] getCodeSources(JarFile jar, URL url) {
        boolean hasUnsigned = unsignedEntryNames(jar).hasMoreElements();

        return mapSignersToCodeSources(url, getJarCodeSigners(), hasUnsigned);
    }

    public CodeSource getCodeSource(URL url, String name) {
        CodeSigner[] signers;

        signers = (CodeSigner[]) signerMap().get(name);
        return mapSignersToCodeSource(url, signers);
    }

    public CodeSource getCodeSource(URL url, JarFile jar, JarEntry je) {
        CodeSigner[] signers;

        return mapSignersToCodeSource(url, getCodeSigners(jar, je));
    }

    public void setEagerValidation(boolean eager) {
        eagerValidation = eager;
    }

    public synchronized List getManifestDigests() {
        return Collections.unmodifiableList(manifestDigests);
    }

    static CodeSource getUnsignedCS(URL url) {
        return new VerifierCodeSource(null, url, (java.security.cert.Certificate[]) null);
    }
}
