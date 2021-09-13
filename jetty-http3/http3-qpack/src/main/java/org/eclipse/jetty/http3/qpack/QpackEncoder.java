//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.http3.qpack.internal.EncodableEntry;
import org.eclipse.jetty.http3.qpack.internal.QpackContext;
import org.eclipse.jetty.http3.qpack.internal.StreamInfo;
import org.eclipse.jetty.http3.qpack.internal.instruction.DuplicateInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.IndexedNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.LiteralNameEntryInstruction;
import org.eclipse.jetty.http3.qpack.internal.instruction.SetCapacityInstruction;
import org.eclipse.jetty.http3.qpack.internal.metadata.Http3Fields;
import org.eclipse.jetty.http3.qpack.internal.parser.EncoderInstructionParser;
import org.eclipse.jetty.http3.qpack.internal.table.DynamicTable;
import org.eclipse.jetty.http3.qpack.internal.table.Entry;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerEncoder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.http3.qpack.QpackException.H3_GENERAL_PROTOCOL_ERROR;
import static org.eclipse.jetty.http3.qpack.QpackException.QPACK_ENCODER_STREAM_ERROR;

public class QpackEncoder implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(QpackEncoder.class);

    public static final EnumSet<HttpHeader> DO_NOT_HUFFMAN =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.PROXY_AUTHENTICATE,
            HttpHeader.PROXY_AUTHORIZATION);

    public static final EnumSet<HttpHeader> DO_NOT_INDEX =
        EnumSet.of(
            // HttpHeader.C_PATH,  // TODO more data needed
            // HttpHeader.DATE,    // TODO more data needed
            HttpHeader.AUTHORIZATION,
            HttpHeader.CONTENT_MD5,
            HttpHeader.CONTENT_RANGE,
            HttpHeader.ETAG,
            HttpHeader.IF_MODIFIED_SINCE,
            HttpHeader.IF_UNMODIFIED_SINCE,
            HttpHeader.IF_NONE_MATCH,
            HttpHeader.IF_RANGE,
            HttpHeader.IF_MATCH,
            HttpHeader.LOCATION,
            HttpHeader.RANGE,
            HttpHeader.RETRY_AFTER,
            // HttpHeader.EXPIRES,
            HttpHeader.LAST_MODIFIED,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);

    // TODO: why do we need this?
    public static final EnumSet<HttpHeader> NEVER_INDEX =
        EnumSet.of(
            HttpHeader.AUTHORIZATION,
            HttpHeader.SET_COOKIE,
            HttpHeader.SET_COOKIE2);

    private final List<Instruction> _instructions = new ArrayList<>();
    private final Instruction.Handler _handler;
    private final QpackContext _context;
    private final int _maxBlockedStreams;
    private final Map<Long, StreamInfo> _streamInfoMap = new HashMap<>();
    private final EncoderInstructionParser _parser;
    private int _knownInsertCount = 0;
    private int _blockedStreams = 0;

    public QpackEncoder(Instruction.Handler handler, int maxBlockedStreams)
    {
        _handler = handler;
        _context = new QpackContext();
        _maxBlockedStreams = maxBlockedStreams;
        _parser = new EncoderInstructionParser(new EncoderAdapter());
    }

    /**
     * Set the capacity of the DynamicTable and send a instruction to set the capacity on the remote Decoder.
     * @param capacity the new capacity.
     */
    public void setCapacity(int capacity) throws QpackException
    {
        _context.getDynamicTable().setCapacity(capacity);
        _handler.onInstructions(List.of(new SetCapacityInstruction(capacity)));
    }

    protected boolean shouldIndex(HttpField httpField)
    {
        return !DO_NOT_INDEX.contains(httpField.getHeader());
    }

    protected boolean shouldHuffmanEncode(HttpField httpField)
    {
        return !DO_NOT_HUFFMAN.contains(httpField.getHeader());
    }

    public void parseInstructionBuffer(ByteBuffer buffer) throws QpackException
    {
        while (BufferUtil.hasContent(buffer))
        {
            _parser.parse(buffer);
        }
        notifyInstructionHandler();
    }

    public boolean insert(HttpField field) throws QpackException
    {
        DynamicTable dynamicTable = _context.getDynamicTable();

        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        boolean canCreateEntry = shouldIndex(field) && dynamicTable.canInsert(field);
        if (!canCreateEntry)
            return false;

        // We can always reference on insertion as it will always arrive before any eviction.
        Entry entry = _context.get(field);
        if (entry != null)
        {
            int index = _context.indexOf(entry);
            dynamicTable.add(new Entry(field));
            _instructions.add(new DuplicateInstruction(index));
            notifyInstructionHandler();
            return true;
        }

        boolean huffman = shouldHuffmanEncode(field);
        Entry nameEntry = _context.get(field.getName());
        if (nameEntry != null)
        {
            int index = _context.indexOf(nameEntry);
            dynamicTable.add(new Entry(field));
            _instructions.add(new IndexedNameEntryInstruction(!nameEntry.isStatic(), index, huffman, field.getValue()));
            notifyInstructionHandler();
            return true;
        }

        dynamicTable.add(new Entry(field));
        _instructions.add(new LiteralNameEntryInstruction(field, huffman));

        notifyInstructionHandler();
        return true;
    }

    public void encode(ByteBuffer buffer, long streamId, MetaData metadata) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Encoding: streamId={}, metadata={}", streamId, metadata);

        // Verify that we can encode without errors.
        if (metadata.getFields() != null)
        {
            for (HttpField field :  metadata.getFields())
            {
                String name = field.getName();
                char firstChar = name.charAt(0);
                if (firstChar <= ' ')
                    throw new QpackException.StreamException(H3_GENERAL_PROTOCOL_ERROR, String.format("Invalid header name: '%s'", name));
            }
        }

        List<EncodableEntry> encodableEntries = new ArrayList<>();
        DynamicTable dynamicTable = _context.getDynamicTable();

        StreamInfo streamInfo = _streamInfoMap.get(streamId);
        if (streamInfo == null)
        {
            streamInfo = new StreamInfo(streamId);
            _streamInfoMap.put(streamId, streamInfo);
        }
        StreamInfo.SectionInfo sectionInfo = new StreamInfo.SectionInfo();
        streamInfo.add(sectionInfo);

        int requiredInsertCount = 0;

        // This will also extract pseudo headers from the metadata.
        Http3Fields httpFields = new Http3Fields(metadata);
        for (HttpField field : httpFields)
        {
            EncodableEntry entry = encode(streamInfo, field);
            encodableEntries.add(entry);

            // Update the required InsertCount.
            int entryRequiredInsertCount = entry.getRequiredInsertCount();
            if (entryRequiredInsertCount > requiredInsertCount)
                requiredInsertCount = entryRequiredInsertCount;
        }

        sectionInfo.setRequiredInsertCount(requiredInsertCount);
        int base = dynamicTable.getBase();
        int encodedInsertCount = encodeInsertCount(requiredInsertCount, dynamicTable.getCapacity());
        boolean signBit = base < requiredInsertCount;
        int deltaBase = signBit ? requiredInsertCount - base - 1 : base - requiredInsertCount;

        // Encode all the entries into the buffer.
        int pos = BufferUtil.flipToFill(buffer);

        // Encode the Field Section Prefix into the ByteBuffer.
        NBitIntegerEncoder.encode(buffer, 8, encodedInsertCount);
        buffer.put(signBit ? (byte)0x80 : (byte)0x00);
        NBitIntegerEncoder.encode(buffer, 7, deltaBase);

        // Encode the field lines into the ByteBuffer.
        for (EncodableEntry entry : encodableEntries)
        {
            entry.encode(buffer, base);
        }

        BufferUtil.flipToFlush(buffer, pos);
        notifyInstructionHandler();
    }

    private EncodableEntry encode(StreamInfo streamInfo, HttpField field)
    {
        DynamicTable dynamicTable = _context.getDynamicTable();

        if (field.getValue() == null)
            field = new HttpField(field.getHeader(), field.getName(), "");

        // TODO: The field.getHeader() could be null.

        if (field instanceof PreEncodedHttpField)
            return EncodableEntry.getPreEncodedEntry((PreEncodedHttpField)field);

        boolean canCreateEntry = shouldIndex(field) && dynamicTable.canInsert(field);

        Entry entry = _context.get(field);
        if (referenceEntry(entry, streamInfo))
        {
            return EncodableEntry.getReferencedEntry(entry);
        }
        else
        {
            // Should we duplicate this entry.
            if (entry != null && canCreateEntry)
            {
                int index = _context.indexOf(entry);
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _instructions.add(new DuplicateInstruction(index));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }
        }

        boolean huffman = shouldHuffmanEncode(field);
        Entry nameEntry = _context.get(field.getName());
        if (referenceEntry(nameEntry, streamInfo))
        {
            // Should we copy this entry
            if (canCreateEntry)
            {
                int index = _context.indexOf(nameEntry);
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _instructions.add(new IndexedNameEntryInstruction(!nameEntry.isStatic(), index, huffman, field.getValue()));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }

            return EncodableEntry.getNameReferencedEntry(nameEntry, field, huffman);
        }
        else
        {
            if (canCreateEntry)
            {
                Entry newEntry = new Entry(field);
                dynamicTable.add(newEntry);
                _instructions.add(new LiteralNameEntryInstruction(field, huffman));

                // Should we reference this entry and risk blocking.
                if (referenceEntry(newEntry, streamInfo))
                    return EncodableEntry.getReferencedEntry(newEntry);
            }

            return EncodableEntry.getLiteralEntry(field, huffman);
        }
    }

    void insertCountIncrement(int increment) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("InsertCountIncrement: increment={}", increment);

        int insertCount = _context.getDynamicTable().getInsertCount();
        if (_knownInsertCount + increment > insertCount)
            throw new QpackException.SessionException(QPACK_ENCODER_STREAM_ERROR, "KnownInsertCount incremented over InsertCount");
        _knownInsertCount += increment;
    }

    void sectionAcknowledgement(long streamId) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("SectionAcknowledgement: streamId={}", streamId);

        StreamInfo streamInfo = _streamInfoMap.get(streamId);
        if (streamInfo == null)
            throw new QpackException.SessionException(QPACK_ENCODER_STREAM_ERROR, "No StreamInfo for " + streamId);

        // The KnownInsertCount should be updated to the earliest sent RequiredInsertCount on that stream.
        StreamInfo.SectionInfo sectionInfo = streamInfo.acknowledge();
        sectionInfo.release();
        _knownInsertCount = Math.max(_knownInsertCount, sectionInfo.getRequiredInsertCount());

        // If we have no more outstanding section acknowledgments remove the StreamInfo.
        if (streamInfo.isEmpty())
            _streamInfoMap.remove(streamId);
    }

    void streamCancellation(long streamId) throws QpackException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("StreamCancellation: streamId={}", streamId);

        StreamInfo streamInfo = _streamInfoMap.remove(streamId);
        if (streamInfo == null)
            throw new QpackException.SessionException(QPACK_ENCODER_STREAM_ERROR, "No StreamInfo for " + streamId);

        // Release all referenced entries outstanding on the stream that was cancelled.
        for (StreamInfo.SectionInfo sectionInfo : streamInfo)
        {
            sectionInfo.release();
        }
    }

    private boolean referenceEntry(Entry entry, StreamInfo streamInfo)
    {
        if (entry == null)
            return false;

        if (entry.isStatic())
            return true;

        boolean inEvictionZone = !_context.getDynamicTable().canReference(entry);
        if (inEvictionZone)
            return false;

        StreamInfo.SectionInfo sectionInfo = streamInfo.getCurrentSectionInfo();

        // If they have already acknowledged this entry we can reference it straight away.
        if (_knownInsertCount >= entry.getIndex() + 1)
        {
            sectionInfo.reference(entry);
            return true;
        }

        // We may need to risk blocking the stream in order to reference it.
        if (streamInfo.isBlocked())
        {
            sectionInfo.block();
            sectionInfo.reference(entry);
            return true;
        }

        if (_blockedStreams < _maxBlockedStreams)
        {
            _blockedStreams++;
            sectionInfo.block();
            sectionInfo.reference(entry);
            return true;
        }

        return false;
    }

    private static int encodeInsertCount(int reqInsertCount, int maxTableCapacity)
    {
        if (reqInsertCount == 0)
            return 0;

        int maxEntries = maxTableCapacity / 32;
        return (reqInsertCount % (2 * maxEntries)) + 1;
    }

    private void notifyInstructionHandler() throws QpackException
    {
        if (!_instructions.isEmpty())
            _handler.onInstructions(_instructions);
        _instructions.clear();
    }

    public class EncoderAdapter implements EncoderInstructionParser.Handler
    {
        @Override
        public void onSectionAcknowledgement(long streamId) throws QpackException
        {
            sectionAcknowledgement(streamId);
        }

        @Override
        public void onStreamCancellation(long streamId) throws QpackException
        {
            streamCancellation(streamId);
        }

        @Override
        public void onInsertCountIncrement(int increment) throws QpackException
        {
            insertCountIncrement(increment);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, _context.getDynamicTable());
    }
}
