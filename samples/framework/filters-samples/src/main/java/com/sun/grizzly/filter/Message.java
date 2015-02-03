/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package com.sun.grizzly.filter;

/**
 * Various Contstants defining the Custom protocol.
 * The Custom protocol is a fixed size Protocol meaning that no
 * message (or one could say packet) can be larger than 8192 bytes.
 *
 * @author John Vieten 22.06.2008
 * @version 1.0
 */
public interface Message {
    static final int HeaderLength = 23;


    static final int Magic = 0x98784F50;

    static final int MagicByteLength = 4;
    static final int MessageMaxLength = 8192;
    static final byte CurrentVersion = 1;
    static final byte Message_Request = 0;
    static final byte Message_Reply = 1;
    static final byte Message_Fragment = 2;
    static final byte Message_Error = 3;

    int getRequestId();

    byte getMessageType();

    boolean moreFragmentsToFollow();

    static final byte MORE_FRAGMENTS_BIT = 0x02;
    static final byte GZIP_BIT = 0x04;
    static final byte APPLICATION_LAYER_ERROR_BIT = 0x08;

    static public enum ErrorCode {
        ERROR_CODE_MAGIC,
        ERROR_CODE_HEADER_FORMAT,
        ERROR_CODE_UNKOWN_MESSAGE_TYPE,
        ERROR_CODE_NO_CONNECTION
    }

}