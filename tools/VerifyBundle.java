// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Verify every bundle payload entry against the expected upload certificate. */
class VerifyBundle {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected bundle and certificate SHA256");
        int verified = 0;
        try (var jar = new JarFile(new File(args[0]), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().matches("(?i)META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)")) continue;
                try (var input = jar.getInputStream(entry)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) throw new SecurityException("bundle payload lacks one signer");
                var certificate = signers[0].getSignerCertPath().getCertificates().getFirst();
                String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                if (!actual.equalsIgnoreCase(args[1])) throw new SecurityException("bundle certificate differs");
                verified++;
            }
        }
        if (verified == 0) throw new SecurityException("bundle has no signed payload");
        System.out.println("bundle signature: " + verified + " payload entries verified");
    }
}
