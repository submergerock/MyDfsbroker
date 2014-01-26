/**
 * Copyright (C) 2007-2012 Hypertable, Inc.
 *
 * This file is part of Hypertable.
 *
 * Hypertable is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or any later version.
 *
 * Hypertable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.hypertable.DfsBroker.hadoop;

import org.hypertable.AsyncComm.Comm;
import org.hypertable.AsyncComm.CommBuf;
import org.hypertable.AsyncComm.CommHeader;
import org.hypertable.AsyncComm.Event;
import org.hypertable.AsyncComm.ResponseCallback;
import org.hypertable.Common.Error;

public class ResponseCallbackPositionRead extends ResponseCallback {

    ResponseCallbackPositionRead(Comm comm, Event event) {
        super(comm, event);
    }

    int response(long offset, int nread, byte [] data) {
        CommHeader header = new CommHeader();
        header.initialize_from_request_header(mEvent.header);
        CommBuf cbuf = new CommBuf(header, 16, data, nread);
        cbuf.AppendInt(Error.OK);
        cbuf.AppendLong(offset);
        cbuf.AppendInt(nread);
        return mComm.SendResponse(mEvent.addr, cbuf);
    }
}

