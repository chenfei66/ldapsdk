/*
 * Copyright 2010-2017 UnboundID Corp.
 * All Rights Reserved.
 */
/*
 * Copyright (C) 2015-2017 UnboundID Corp.
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
package com.unboundid.ldap.sdk.unboundidds.extensions;



import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.IntermediateResponse;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.Debug;
import com.unboundid.util.NotMutable;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.ThreadSafety;
import com.unboundid.util.ThreadSafetyLevel;

import static com.unboundid.ldap.sdk.unboundidds.extensions.ExtOpMessages.*;



/**
 * This class provides an implementation of an intermediate response which
 * indicates that the Directory Server may have already purged information about
 * one or more changes.
 * <BR>
 * <BLOCKQUOTE>
 *   <B>NOTE:</B>  This class, and other classes within the
 *   {@code com.unboundid.ldap.sdk.unboundidds} package structure, are only
 *   supported for use against Ping Identity, UnboundID, and Alcatel-Lucent 8661
 *   server products.  These classes provide support for proprietary
 *   functionality or for external specifications that are not considered stable
 *   or mature enough to be guaranteed to work in an interoperable way with
 *   other types of LDAP servers.
 * </BLOCKQUOTE>
 * <BR>
 * The missing changelog entries intermediate response value may be present, and
 * if it is then it will have the following encoding:
 * <PRE>
 *   MissingEntriesIntermediateResponse ::= SEQUENCE {
 *        message     [0] OCTET STRING OPTIONAL,
 *        ... }
 * </PRE>
 */
@NotMutable()
@ThreadSafety(level=ThreadSafetyLevel.COMPLETELY_THREADSAFE)
public final class MissingChangelogEntriesIntermediateResponse
       extends IntermediateResponse
{
  /**
   * The OID (1.3.6.1.4.1.30221.2.6.12) for the get stream directory values
   * intermediate response.
   */
  public static final String
       MISSING_CHANGELOG_ENTRIES_INTERMEDIATE_RESPONSE_OID =
            "1.3.6.1.4.1.30221.2.6.12";



  /**
   * The BER type for the response message.
   */
  private static final byte TYPE_MESSAGE = (byte) 0x80;



  /**
   * The serial version UID for this serializable class.
   */
  private static final long serialVersionUID = -4961560327295588578L;



  // A message which may provide additional information about the missing
  // changes.
  private final String message;



  /**
   * Creates a new missing changelog entries intermediate response with the
   * provided information.
   *
   * @param  message   A message which may provide additional information about
   *                   the missing changes.  It may be {@code null} if no
   *                   message is available.
   * @param  controls  The set of controls to include in the intermediate
   *                   response.  It may be {@code null} or empty if no controls
   *                   should be included.
   */
  public MissingChangelogEntriesIntermediateResponse(final String message,
                                                     final Control... controls)
  {
    super(MISSING_CHANGELOG_ENTRIES_INTERMEDIATE_RESPONSE_OID,
          encodeValue(message), controls);

    this.message = message;
  }



  /**
   * Creates a new missing changelog entries intermediate response from the
   * provided generic intermediate response.
   *
   * @param  r  The generic intermediate response to be decoded.
   *
   * @throws  LDAPException  If the provided intermediate response cannot be
   *                         decoded as a missing changelog entries response.
   */
  public MissingChangelogEntriesIntermediateResponse(
              final IntermediateResponse r)
         throws LDAPException
  {
    super(r);

    final ASN1OctetString value = r.getValue();
    if (value == null)
    {
      message = null;
      return;
    }

    final ASN1Sequence valueSequence;
    try
    {
      valueSequence = ASN1Sequence.decodeAsSequence(value.getValue());
    }
    catch (final Exception e)
    {
      Debug.debugException(e);
      throw new LDAPException(ResultCode.DECODING_ERROR,
           ERR_MISSING_CHANGELOG_ENTRIES_IR_VALUE_NOT_SEQUENCE.get(
                StaticUtils.getExceptionMessage(e)), e);
    }

    String msg = null;
    for (final ASN1Element e : valueSequence.elements())
    {
      final byte type = e.getType();
      switch (type)
      {
        case TYPE_MESSAGE:
          msg = ASN1OctetString.decodeAsOctetString(e).stringValue();
          break;
        default:
          throw new LDAPException(ResultCode.DECODING_ERROR,
               ERR_MISSING_CHANGELOG_ENTRIES_IR_UNEXPECTED_VALUE_TYPE.get(
                    StaticUtils.toHex(type)));
      }
    }

    message = msg;
  }



  /**
   * Encodes the provided information in a form suitable for use as the value of
   * this intermediate response.
   *
   * @param  message  A message which may provide additional information about
   *                  the missing changes.  It may be {@code null} if no message
   *                  is available.
   *
   * @return  The encoded value, or {@code null} if no value should be included
   *          in the intermediate response.
   */
  private static ASN1OctetString encodeValue(final String message)
  {
    if (message == null)
    {
      return null;
    }

    final ASN1Sequence valueSequence = new ASN1Sequence(
         new ASN1OctetString(TYPE_MESSAGE, message));
    return new ASN1OctetString(valueSequence.encode());
  }



  /**
   * Retrieves a message which may provide additional information about the
   * missing changes.
   *
   * @return  A message which may provide additional information about the
   *          missing changes, or {@code null} if none is available.
   */
  public String getMessage()
  {
    return message;
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public String getIntermediateResponseName()
  {
    return INFO_MISSING_CHANGELOG_ENTRIES_IR_NAME.get();
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public String valueToString()
  {
    if (message == null)
    {
      return null;
    }

    final StringBuilder buffer = new StringBuilder();

    buffer.append("message='");
    buffer.append(message);
    buffer.append('\'');

    return buffer.toString();
  }



  /**
   * {@inheritDoc}
   */
  @Override()
  public void toString(final StringBuilder buffer)
  {
    buffer.append("MissingChangelogEntriesIntermediateResponse(");

    boolean appended = false;
    final int messageID = getMessageID();
    if (messageID >= 0)
    {
      buffer.append("messageID=");
      buffer.append(messageID);
      appended = true;
    }

    if (message != null)
    {
      if (appended)
      {
        buffer.append(", ");
      }

      buffer.append("message='");
      buffer.append(message);
      buffer.append('\'');
      appended = true;
    }

    final Control[] controls = getControls();
    if (controls.length > 0)
    {
      if (appended)
      {
        buffer.append(", ");
      }

      buffer.append("controls={");
      for (int i=0; i < controls.length; i++)
      {
        if (i > 0)
        {
          buffer.append(", ");
        }

        buffer.append(controls[i]);
      }
      buffer.append('}');
    }

    buffer.append(')');
  }
}
