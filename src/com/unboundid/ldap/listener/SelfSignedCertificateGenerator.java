/*
 * Copyright 2019 Ping Identity Corporation
 * All Rights Reserved.
 */
/*
 * Copyright (C) 2019 Ping Identity Corporation
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */
package com.unboundid.ldap.listener;



import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.NameResolver;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.Base64;
import com.unboundid.util.Debug;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;
import com.unboundid.util.ssl.cert.CertException;
import com.unboundid.util.ssl.cert.ManageCertificates;

import static com.unboundid.ldap.listener.ListenerMessages.*;



/**
 * This class provides a mechanism for generating a self-signed certificate for
 * use by a listener that supports SSL or StartTLS.
 */
@ThreadSafety(level= ThreadSafetyLevel.NOT_THREADSAFE)
public final class SelfSignedCertificateGenerator
{
  /**
   * Prevent this utility class from being instantiated.
   */
  private SelfSignedCertificateGenerator()
  {
    // No implementation is required.
  }



  /**
   * Generates a temporary keystore containing a self-signed certificate for
   * use by a listener that supports SSL or StartTLS.
   *
   * @param  toolName      The name of the tool for which the certificate is to
   *                       be generated.
   * @param  keyStoreType  The key store type for the keystore to be created.
   *                       It must not be {@code null}.
   *
   * @return  An {@code ObjectPair} containing the path and PIN for the keystore
   *          that was generated.
   *
   * @throws  CertException  If a problem occurs while trying to generate the
   *                         temporary keystore containing the self-signed
   *                         certificate.
   */
  public static ObjectPair<File,char[]> generateTemporarySelfSignedCertificate(
                                             final String toolName,
                                             final String keyStoreType)
         throws CertException
  {
    final File keyStoreFile;
    try
    {
      keyStoreFile = File.createTempFile("temp-keystore-", ".jks");
    }
    catch (final Exception e)
    {
      Debug.debugException(e);
      throw new CertException(
           ERR_SELF_SIGNED_CERT_GENERATOR_CANNOT_CREATE_FILE.get(
                StaticUtils.getExceptionMessage(e)),
           e);
    }

    keyStoreFile.delete();

    final SecureRandom random = new SecureRandom();
    final byte[] randomBytes = new byte[50];
    random.nextBytes(randomBytes);
    final String keyStorePIN = Base64.encode(randomBytes);

    generateSelfSignedCertificate(toolName, keyStoreFile, keyStorePIN,
         keyStoreType, "server-cert");
    return new ObjectPair<>(keyStoreFile, keyStorePIN.toCharArray());
  }



  /**
   * Generates a self-signed certificate in the specified keystore.
   *
   * @param  toolName      The name of the tool for which the certificate is to
   *                       be generated.
   * @param  keyStoreFile  The path to the keystore file in which the
   *                       certificate is to be generated.  This must not be
   *                       {@code null}, and if the target file exists, then it
   *                       must be a JKS or PKCS #12 keystore.  If it does not
   *                       exist, then at least the parent directory must exist.
   * @param  keyStorePIN   The PIN needed to access the keystore.  It must not
   *                       be {@code null}.
   * @param  keyStoreType  The key store type for the keystore to be created, if
   *                       it does not already exist.  It must not be
   *                       {@code null}.
   * @param  alias         The alias to use for the certificate in the keystore.
   *                       It must not be {@code null}.
   *
   * @throws  CertException  If a problem occurs while trying to generate
   *                         self-signed certificate.
   */
  public static void generateSelfSignedCertificate(final String toolName,
                                                   final File keyStoreFile,
                                                   final String keyStorePIN,
                                                   final String keyStoreType,
                                                   final String alias)
         throws CertException
  {
    // Try to get a list of all addresses associated with the system, and all of
    // the addresses associated with them.
    final NameResolver nameResolver =
         LDAPConnectionOptions.DEFAULT_NAME_RESOLVER;
    final LinkedHashSet<InetAddress> localAddresses = new LinkedHashSet<>(20);

    try
    {
      localAddresses.add(nameResolver.getLocalHost());
    }
    catch (final Exception e)
    {
      Debug.debugException(e);
    }

    try
    {
      final Enumeration<NetworkInterface> networkInterfaces =
           NetworkInterface.getNetworkInterfaces();
      while (networkInterfaces.hasMoreElements())
      {
        final NetworkInterface networkInterface =
             networkInterfaces.nextElement();
        final Enumeration<InetAddress> interfaceAddresses =
             networkInterface.getInetAddresses();
        while (interfaceAddresses.hasMoreElements())
        {
          localAddresses.add(interfaceAddresses.nextElement());
        }
      }
    }
    catch (final Exception e)
    {
      Debug.debugException(e);
    }

    try
    {
      localAddresses.add(nameResolver.getLoopbackAddress());
    }
    catch (final Exception e)
    {
      Debug.debugException(e);
    }


    // Get canonical names for all of the local addresses.
    final LinkedHashSet<String> localAddressNames = new LinkedHashSet<>(20);
    for (final InetAddress localAddress : localAddresses)
    {
      final String hostAddress = localAddress.getHostAddress();
      final String trimmedHostAddress = trimHostAddress(hostAddress);
      final String canonicalHostName =
           nameResolver.getCanonicalHostName(localAddress);
      if (! (canonicalHostName.equalsIgnoreCase(hostAddress) ||
           canonicalHostName.equalsIgnoreCase(trimmedHostAddress)))
      {
        localAddressNames.add(canonicalHostName);
      }
    }


    // Construct a subject DN for the certificate.
    final DN subjectDN;
    if (localAddresses.isEmpty())
    {
      subjectDN = new DN(new RDN("CN", toolName));
    }
    else
    {
      subjectDN = new DN(
           new RDN("CN",
                nameResolver.getCanonicalHostName(
                     localAddresses.iterator().next())),
           new RDN("OU", toolName));
    }


    // Generate a timestamp that corresponds to one day ago.
    final long oneDayAgoTime = System.currentTimeMillis() - 86_400_000L;
    final Date oneDayAgoDate = new Date(oneDayAgoTime);
    final SimpleDateFormat dateFormatter =
         new SimpleDateFormat("yyyyMMddHHmmss");
    final String yesterdayTimeStamp = dateFormatter.format(oneDayAgoDate);


    // Build the list of arguments to provide to the manage-certificates tool.
    final ArrayList<String> argList = new ArrayList<>(30);
    argList.add("generate-self-signed-certificate");

    argList.add("--keystore");
    argList.add(keyStoreFile.getAbsolutePath());

    argList.add("--keystore-password");
    argList.add(keyStorePIN);

    argList.add("--keystore-type");
    argList.add(keyStoreType);

    argList.add("--alias");
    argList.add(alias);

    argList.add("--subject-dn");
    argList.add(subjectDN.toString());

    argList.add("--days-valid");
    argList.add("3650");

    argList.add("--validityStartTime");
    argList.add(yesterdayTimeStamp);

    argList.add("--key-algorithm");
    argList.add("RSA");

    argList.add("--key-size-bits");
    argList.add("2048");

    argList.add("--signature-algorithm");
    argList.add("SHA256withRSA");

    for (final String hostName : localAddressNames)
    {
      argList.add("--subject-alternative-name-dns");
      argList.add(hostName);
    }

    for (final InetAddress address : localAddresses)
    {
      argList.add("--subject-alternative-name-ip-address");
      argList.add(trimHostAddress(address.getHostAddress()));
    }

    argList.add("--key-usage");
    argList.add("digitalSignature");
    argList.add("--key-usage");
    argList.add("keyEncipherment");

    argList.add("--extended-key-usage");
    argList.add("server-auth");
    argList.add("--extended-key-usage");
    argList.add("client-auth");

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final ResultCode resultCode = ManageCertificates.main(null, output, output,
         argList.toArray(StaticUtils.NO_STRINGS));
    if (resultCode != ResultCode.SUCCESS)
    {
      throw new CertException(
           ERR_SELF_SIGNED_CERT_GENERATOR_ERROR_GENERATING_CERT.get(
                StaticUtils.toUTF8String(output.toByteArray())));
    }
  }



  /**
   * Java sometimes follows an IP address with a percent sign and the interface
   * name.  If the provided host address contains an interface name, then trim
   * it off.
   *
   * @param  hostAddress  The host address to be trimmed.
   *
   * @return  The provided host name without an interface name.
   */
  private static String trimHostAddress(final String hostAddress)
  {
    final int percentPos = hostAddress.indexOf('%');
    final String trimmedHostAddress;
    if (percentPos > 0)
    {
      return hostAddress.substring(0, percentPos);
    }
    else
    {
      return hostAddress;
    }
  }
}
