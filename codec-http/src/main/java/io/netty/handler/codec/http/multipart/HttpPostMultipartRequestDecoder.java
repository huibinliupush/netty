/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.HttpPostBodyUtil.SeekAheadOptimize;
import io.netty.handler.codec.http.multipart.HttpPostBodyUtil.TransferEncodingMechanism;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.MultiPartStatus;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.NotEnoughDataDecoderException;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.netty.util.internal.ObjectUtil.*;

/**
 * This decoder will decode Body and can handle POST BODY.
 *
 * You <strong>MUST</strong> call {@link #destroy()} after completion to release all resources.
 *
 */
public class HttpPostMultipartRequestDecoder implements InterfaceHttpPostRequestDecoder {

    /**
     * Factory used to create InterfaceHttpData
     */
    private final HttpDataFactory factory;

    /**
     * Request to decode
     */
    private final HttpRequest request;

    /**
     * Default charset to use
     */
    private Charset charset;

    /**
     * Does the last chunk already received
     */
    private boolean isLastChunk;

    /**
     * HttpDatas from Body
     */
    private final List<InterfaceHttpData> bodyListHttpData = new ArrayList<InterfaceHttpData>();

    /**
     * HttpDatas as Map from Body
     */
    private final Map<String, List<InterfaceHttpData>> bodyMapHttpData = new TreeMap<String, List<InterfaceHttpData>>(
            CaseIgnoringComparator.INSTANCE);

    /**
     * The current channelBuffer
     */
    private ByteBuf undecodedChunk;

    /**
     * Body HttpDatas current position
     */
    private int bodyListHttpDataRank;

    /**
     * If multipart, this is the boundary for the global multipart
     */
    private String multipartDataBoundary;

    /**
     * If multipart, there could be internal multiparts (mixed) to the global
     * multipart. Only one level is allowed.
     */
    private String multipartMixedBoundary;

    /**
     * Current getStatus
     */
    private MultiPartStatus currentStatus = MultiPartStatus.NOTSTARTED;

    /**
     * Used in Multipart
     */
    private Map<CharSequence, Attribute> currentFieldAttributes;

    /**
     * The current FileUpload that is currently in decode process
     */
    private FileUpload currentFileUpload;

    /**
     * The current Attribute that is currently in decode process
     */
    private Attribute currentAttribute;

    /**
     * The current Data position before finding delimiter
     */
    private int lastDataPosition;

    private boolean destroyed;

    private int discardThreshold = HttpPostRequestDecoder.DEFAULT_DISCARD_THRESHOLD;

    /**
     *
     * @param request
     *            the request to decode
     * @throws NullPointerException
     *             for request
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpRequest request) {
        this(new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE), request, HttpConstants.DEFAULT_CHARSET);
    }

    /**
     *
     * @param factory
     *            the factory used to create InterfaceHttpData
     * @param request
     *            the request to decode
     * @throws NullPointerException
     *             for request or factory
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request) {
        this(factory, request, HttpConstants.DEFAULT_CHARSET);
    }

    /**
     *
     * @param factory
     *            the factory used to create InterfaceHttpData
     * @param request
     *            the request to decode
     * @param charset
     *            the charset to use as default
     * @throws NullPointerException
     *             for request or charset or factory
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset) {
        this.request = checkNotNull(request, "request");
        this.charset = checkNotNull(charset, "charset");
        this.factory = checkNotNull(factory, "factory");
        // Fill default values

        setMultipart(this.request.headers().get(HttpHeaderNames.CONTENT_TYPE));
        if (request instanceof HttpContent) {
            // Offer automatically if the given request is als type of HttpContent
            // See #1089
            offer((HttpContent) request);
        } else {
            parseBody();
        }
    }

    /**
     * Set from the request ContentType the multipartDataBoundary and the possible charset.
     */
    private void setMultipart(String contentType) {
        String[] dataBoundary = HttpPostRequestDecoder.getMultipartDataBoundary(contentType);
        if (dataBoundary != null) {
            multipartDataBoundary = dataBoundary[0];
            if (dataBoundary.length > 1 && dataBoundary[1] != null) {
                charset = Charset.forName(dataBoundary[1]);
            }
        } else {
            multipartDataBoundary = null;
        }
        currentStatus = MultiPartStatus.HEADERDELIMITER;
    }

    private void checkDestroyed() {
        if (destroyed) {
            throw new IllegalStateException(HttpPostMultipartRequestDecoder.class.getSimpleName()
                    + " was destroyed already");
        }
    }

    /**
     * True if this request is a Multipart request
     *
     * @return True if this request is a Multipart request
     */
    @Override
    public boolean isMultipart() {
        checkDestroyed();
        return true;
    }

    /**
     * Set the amount of bytes after which read bytes in the buffer should be discarded.
     * Setting this lower gives lower memory usage but with the overhead of more memory copies.
     * Use {@code 0} to disable it.
     */
    @Override
    public void setDiscardThreshold(int discardThreshold) {
        this.discardThreshold = checkPositiveOrZero(discardThreshold, "discardThreshold");
    }

    /**
     * Return the threshold in bytes after which read data in the buffer should be discarded.
     */
    @Override
    public int getDiscardThreshold() {
        return discardThreshold;
    }

    /**
     * This getMethod returns a List of all HttpDatas from body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return the list of HttpDatas from Body part for POST getMethod
     * @throws NotEnoughDataDecoderException
     *             Need more chunks
     */
    @Override
    public List<InterfaceHttpData> getBodyHttpDatas() {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        return bodyListHttpData;
    }

    /**
     * This getMethod returns a List of all HttpDatas with the given name from
     * body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return All Body HttpDatas with the given name (ignore case)
     * @throws NotEnoughDataDecoderException
     *             need more chunks
     */
    @Override
    public List<InterfaceHttpData> getBodyHttpDatas(String name) {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        return bodyMapHttpData.get(name);
    }

    /**
     * This getMethod returns the first InterfaceHttpData with the given name from
     * body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return The first Body InterfaceHttpData with the given name (ignore
     *         case)
     * @throws NotEnoughDataDecoderException
     *             need more chunks
     */
    @Override
    public InterfaceHttpData getBodyHttpData(String name) {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        List<InterfaceHttpData> list = bodyMapHttpData.get(name);
        if (list != null) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Initialized the internals from a new chunk
     *
     * @param content
     *            the new received chunk
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    @Override
    public HttpPostMultipartRequestDecoder offer(HttpContent content) {
        checkDestroyed();

        if (content instanceof LastHttpContent) {
            isLastChunk = true;
        }

        ByteBuf buf = content.content();
        if (undecodedChunk == null) {
            undecodedChunk = isLastChunk ?
                    // Take a slice instead of copying when the first chunk is also the last
                    // as undecodedChunk.writeBytes will never be called.
                    buf.retainedSlice() :
                    // Maybe we should better not copy here for performance reasons but this will need
                    // more care by the caller to release the content in a correct manner later
                    // So maybe something to optimize on a later stage
                    //
                    // We are explicit allocate a buffer and NOT calling copy() as otherwise it may set a maxCapacity
                    // which is not really usable for us as we may exceed it once we add more bytes.
                    buf.alloc().buffer(buf.readableBytes()).writeBytes(buf);
        } else {
            int readPos = undecodedChunk.readerIndex();
            int writable = undecodedChunk.writableBytes();
            int toWrite = buf.readableBytes();
            // 这里的 readPos 表示可以丢弃的字节个数（已经读取的）
            // writable < toWrite 表示当前 undecodedChunk 已经放不下了
            // readPos + writable 表示当前 undecodedChunk 可以写入的字节数: 可丢弃的字节 + 可写的字节
            if (undecodedChunk.refCnt() == 1 && writable < toWrite && readPos + writable >= toWrite) {
                undecodedChunk.discardReadBytes();
            }
            undecodedChunk.writeBytes(buf);
        }
        parseBody();
        return this;
    }

    /**
     * True if at current getStatus, there is an available decoded
     * InterfaceHttpData from the Body.
     *
     * This getMethod works for chunked and not chunked request.
     *
     * @return True if at current getStatus, there is a decoded InterfaceHttpData
     * @throws EndOfDataDecoderException
     *             No more data will be available
     */
    @Override
    public boolean hasNext() {
        checkDestroyed();

        if (currentStatus == MultiPartStatus.EPILOGUE) {
            // OK except if end of list
            if (bodyListHttpDataRank >= bodyListHttpData.size()) {
                throw new EndOfDataDecoderException();
            }
        }
        return !bodyListHttpData.isEmpty() && bodyListHttpDataRank < bodyListHttpData.size();
    }

    /**
     * Returns the next available InterfaceHttpData or null if, at the time it
     * is called, there is no more available InterfaceHttpData. A subsequent
     * call to offer(httpChunk) could enable more data.
     *
     * Be sure to call {@link InterfaceHttpData#release()} after you are done
     * with processing to make sure to not leak any resources
     *
     * @return the next available InterfaceHttpData or null if none
     * @throws EndOfDataDecoderException
     *             No more data will be available
     */
    @Override
    public InterfaceHttpData next() {
        checkDestroyed();

        if (hasNext()) {
            return bodyListHttpData.get(bodyListHttpDataRank++);
        }
        return null;
    }

    @Override
    public InterfaceHttpData currentPartialHttpData() {
        if (currentFileUpload != null) {
            return currentFileUpload;
        } else {
            return currentAttribute;
        }
    }

    /**
     * This getMethod will parse as much as possible data and fill the list and map
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBody() {
        if (currentStatus == MultiPartStatus.PREEPILOGUE || currentStatus == MultiPartStatus.EPILOGUE) {
            if (isLastChunk) {
                currentStatus = MultiPartStatus.EPILOGUE;
            }
            return;
        }
        parseBodyMultipart();
    }

    /**
     * Utility function to add a new decoded data
     */
    protected void addHttpData(InterfaceHttpData data) {
        if (data == null) {
            return;
        }
        List<InterfaceHttpData> datas = bodyMapHttpData.get(data.getName());
        if (datas == null) {
            datas = new ArrayList<InterfaceHttpData>(1);
            bodyMapHttpData.put(data.getName(), datas);
        }
        datas.add(data);
        bodyListHttpData.add(data);
    }

    /**
     * Parse the Body for multipart
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBodyMultipart() {
        if (undecodedChunk == null || undecodedChunk.readableBytes() == 0) {
            // nothing to decode
            return;
        }
        InterfaceHttpData data = decodeMultipart(currentStatus);
        while (data != null) {
            addHttpData(data);
            if (currentStatus == MultiPartStatus.PREEPILOGUE || currentStatus == MultiPartStatus.EPILOGUE) {
                break;
            }
            data = decodeMultipart(currentStatus);
        }
    }

    /**
     * Decode a multipart request by pieces<br>
     * <br>
     * NOTSTARTED PREAMBLE (<br>
     * (HEADERDELIMITER DISPOSITION (FIELD | FILEUPLOAD))*<br>
     * (HEADERDELIMITER DISPOSITION MIXEDPREAMBLE<br>
     * (MIXEDDELIMITER MIXEDDISPOSITION MIXEDFILEUPLOAD)+<br>
     * MIXEDCLOSEDELIMITER)*<br>
     * CLOSEDELIMITER)+ EPILOGUE<br>
     *
     * Inspired from HttpMessageDecoder
     *
     * @return the next decoded InterfaceHttpData or null if none until now.
     * @throws ErrorDataDecoderException
     *             if an error occurs
     */
    private InterfaceHttpData decodeMultipart(MultiPartStatus state) {
        switch (state) {
        case NOTSTARTED:
            throw new ErrorDataDecoderException("Should not be called with the current getStatus");
        case PREAMBLE:
            // Content-type: multipart/form-data, boundary=AaB03x
            throw new ErrorDataDecoderException("Should not be called with the current getStatus");
        case HEADERDELIMITER: {
            // --AaB03x or --AaB03x--
            return findMultipartDelimiter(multipartDataBoundary, MultiPartStatus.DISPOSITION,
                    MultiPartStatus.PREEPILOGUE);
        }
        case DISPOSITION: {
            // content-disposition: form-data; name="field1"
            // content-disposition: form-data; name="pics"; filename="file1.txt"
            // and other immediate values like
            // Content-type: image/gif
            // Content-Type: text/plain
            // Content-Type: text/plain; charset=ISO-8859-1
            // Content-Transfer-Encoding: binary
            // The following line implies a change of mode (mixed mode)
            // Content-type: multipart/mixed, boundary=BbC04y
            return findMultipartDisposition();
        }
        case FIELD: {
            // Now get value according to Content-Type and Charset
            Charset localCharset = null;
            Attribute charsetAttribute = currentFieldAttributes.get(HttpHeaderValues.CHARSET);
            if (charsetAttribute != null) {
                try {
                    localCharset = Charset.forName(charsetAttribute.getValue());
                } catch (IOException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (UnsupportedCharsetException e) {
                    throw new ErrorDataDecoderException(e);
                }
            }
            Attribute nameAttribute = currentFieldAttributes.get(HttpHeaderValues.NAME);
            if (currentAttribute == null) {
                Attribute lengthAttribute = currentFieldAttributes
                        .get(HttpHeaderNames.CONTENT_LENGTH);
                long size;
                try {
                    size = lengthAttribute != null? Long.parseLong(lengthAttribute
                            .getValue()) : 0L;
                } catch (IOException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (NumberFormatException ignored) {
                    size = 0;
                }
                try {
                    if (size > 0) {
                        currentAttribute = factory.createAttribute(request,
                                cleanString(nameAttribute.getValue()), size);
                    } else {
                        currentAttribute = factory.createAttribute(request,
                                cleanString(nameAttribute.getValue()));
                    }
                } catch (NullPointerException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (IllegalArgumentException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (IOException e) {
                    throw new ErrorDataDecoderException(e);
                }
                if (localCharset != null) {
                    currentAttribute.setCharset(localCharset);
                }
            }
            // load data
            if (!loadDataMultipart(undecodedChunk, multipartDataBoundary, currentAttribute)) {
                // Delimiter is not found. Need more chunks.
                return null;
            }
            Attribute finalAttribute = currentAttribute;
            currentAttribute = null;
            currentFieldAttributes = null;
            // ready to load the next one
            currentStatus = MultiPartStatus.HEADERDELIMITER;
            return finalAttribute;
        }
        case FILEUPLOAD: {
            // eventually restart from existing FileUpload
            return getFileUpload(multipartDataBoundary);
        }
        case MIXEDDELIMITER: {
            // --AaB03x or --AaB03x--
            // Note that currentFieldAttributes exists
            return findMultipartDelimiter(multipartMixedBoundary, MultiPartStatus.MIXEDDISPOSITION,
                    MultiPartStatus.HEADERDELIMITER);
        }
        case MIXEDDISPOSITION: {
            return findMultipartDisposition();
        }
        case MIXEDFILEUPLOAD: {
            // eventually restart from existing FileUpload
            return getFileUpload(multipartMixedBoundary);
        }
        case PREEPILOGUE:
            return null;
        case EPILOGUE:
            return null;
        default:
            throw new ErrorDataDecoderException("Shouldn't reach here.");
        }
    }

    /**
     * Skip control Characters
     *
     * @throws NotEnoughDataDecoderException
     */
    private static void skipControlCharacters(ByteBuf undecodedChunk) {
        if (!undecodedChunk.hasArray()) {
            try {
                skipControlCharactersStandard(undecodedChunk);
            } catch (IndexOutOfBoundsException e1) {
                throw new NotEnoughDataDecoderException(e1);
            }
            return;
        }
        SeekAheadOptimize sao = new SeekAheadOptimize(undecodedChunk);
        while (sao.pos < sao.limit) {
            char c = (char) (sao.bytes[sao.pos++] & 0xFF);
            if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
                sao.setReadPosition(1);
                return;
            }
        }
        throw new NotEnoughDataDecoderException("Access out of bounds");
    }

    private static void skipControlCharactersStandard(ByteBuf undecodedChunk) {
        for (;;) {
            char c = (char) undecodedChunk.readUnsignedByte();
            if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
                undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
                break;
            }
        }
    }

    /**
     * Find the next Multipart Delimiter
     *
     * @param delimiter
     *            delimiter to find
     * @param dispositionStatus
     *            the next getStatus if the delimiter is a start
     * @param closeDelimiterStatus
     *            the next getStatus if the delimiter is a close delimiter
     * @return the next InterfaceHttpData if any
     * @throws ErrorDataDecoderException
     */
    private InterfaceHttpData findMultipartDelimiter(String delimiter, MultiPartStatus dispositionStatus,
            MultiPartStatus closeDelimiterStatus) {
        // --AaB03x or --AaB03x--
        int readerIndex = undecodedChunk.readerIndex();
        try {
            skipControlCharacters(undecodedChunk);
        } catch (NotEnoughDataDecoderException ignored) {
            undecodedChunk.readerIndex(readerIndex);
            return null;
        }
        skipOneLine();
        String newline;
        try {
            newline = readDelimiter(undecodedChunk, delimiter);
        } catch (NotEnoughDataDecoderException ignored) {
            undecodedChunk.readerIndex(readerIndex);
            return null;
        }
        if (newline.equals(delimiter)) {
            currentStatus = dispositionStatus;
            return decodeMultipart(dispositionStatus);
        }
        if (newline.equals(delimiter + "--")) {
            // CLOSEDELIMITER or MIXED CLOSEDELIMITER found
            currentStatus = closeDelimiterStatus;
            if (currentStatus == MultiPartStatus.HEADERDELIMITER) {
                // MIXEDCLOSEDELIMITER
                // end of the Mixed part
                currentFieldAttributes = null;
                return decodeMultipart(MultiPartStatus.HEADERDELIMITER);
            }
            return null;
        }
        undecodedChunk.readerIndex(readerIndex);
        throw new ErrorDataDecoderException("No Multipart delimiter found");
    }

    /**
     * Find the next Disposition
     *
     * @return the next InterfaceHttpData if any
     * @throws ErrorDataDecoderException
     */
    private InterfaceHttpData findMultipartDisposition() {
        int readerIndex = undecodedChunk.readerIndex();
        if (currentStatus == MultiPartStatus.DISPOSITION) {
            currentFieldAttributes = new TreeMap<CharSequence, Attribute>(CaseIgnoringComparator.INSTANCE);
        }
        // read many lines until empty line with newline found! Store all data
        while (!skipOneLine()) {
            String newline;
            try {
                skipControlCharacters(undecodedChunk);
                newline = readLine(undecodedChunk, charset);
            } catch (NotEnoughDataDecoderException ignored) {
                undecodedChunk.readerIndex(readerIndex);
                return null;
            }
            String[] contents = splitMultipartHeader(newline);
            if (HttpHeaderNames.CONTENT_DISPOSITION.contentEqualsIgnoreCase(contents[0])) {
                boolean checkSecondArg;
                if (currentStatus == MultiPartStatus.DISPOSITION) {
                    checkSecondArg = HttpHeaderValues.FORM_DATA.contentEqualsIgnoreCase(contents[1]);
                } else {
                    checkSecondArg = HttpHeaderValues.ATTACHMENT.contentEqualsIgnoreCase(contents[1])
                            || HttpHeaderValues.FILE.contentEqualsIgnoreCase(contents[1]);
                }
                if (checkSecondArg) {
                    // read next values and store them in the map as Attribute
                    for (int i = 2; i < contents.length; i++) {
                        String[] values = contents[i].split("=", 2);
                        Attribute attribute;
                        try {
                            attribute = getContentDispositionAttribute(values);
                        } catch (NullPointerException e) {
                            throw new ErrorDataDecoderException(e);
                        } catch (IllegalArgumentException e) {
                            throw new ErrorDataDecoderException(e);
                        }
                        currentFieldAttributes.put(attribute.getName(), attribute);
                    }
                }
            } else if (HttpHeaderNames.CONTENT_TRANSFER_ENCODING.contentEqualsIgnoreCase(contents[0])) {
                Attribute attribute;
                try {
                    attribute = factory.createAttribute(request, HttpHeaderNames.CONTENT_TRANSFER_ENCODING.toString(),
                            cleanString(contents[1]));
                } catch (NullPointerException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (IllegalArgumentException e) {
                    throw new ErrorDataDecoderException(e);
                }

                currentFieldAttributes.put(HttpHeaderNames.CONTENT_TRANSFER_ENCODING, attribute);
            } else if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(contents[0])) {
                Attribute attribute;
                try {
                    attribute = factory.createAttribute(request, HttpHeaderNames.CONTENT_LENGTH.toString(),
                            cleanString(contents[1]));
                } catch (NullPointerException e) {
                    throw new ErrorDataDecoderException(e);
                } catch (IllegalArgumentException e) {
                    throw new ErrorDataDecoderException(e);
                }

                currentFieldAttributes.put(HttpHeaderNames.CONTENT_LENGTH, attribute);
            } else if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(contents[0])) {
                // Take care of possible "multipart/mixed"
                if (HttpHeaderValues.MULTIPART_MIXED.contentEqualsIgnoreCase(contents[1])) {
                    if (currentStatus == MultiPartStatus.DISPOSITION) {
                        String values = StringUtil.substringAfter(contents[2], '=');
                        multipartMixedBoundary = "--" + values;
                        currentStatus = MultiPartStatus.MIXEDDELIMITER;
                        return decodeMultipart(MultiPartStatus.MIXEDDELIMITER);
                    } else {
                        throw new ErrorDataDecoderException("Mixed Multipart found in a previous Mixed Multipart");
                    }
                } else {
                    for (int i = 1; i < contents.length; i++) {
                        final String charsetHeader = HttpHeaderValues.CHARSET.toString();
                        if (contents[i].regionMatches(true, 0, charsetHeader, 0, charsetHeader.length())) {
                            String values = StringUtil.substringAfter(contents[i], '=');
                            Attribute attribute;
                            try {
                                attribute = factory.createAttribute(request, charsetHeader, cleanString(values));
                            } catch (NullPointerException e) {
                                throw new ErrorDataDecoderException(e);
                            } catch (IllegalArgumentException e) {
                                throw new ErrorDataDecoderException(e);
                            }
                            currentFieldAttributes.put(HttpHeaderValues.CHARSET, attribute);
                        } else {
                            Attribute attribute;
                            try {
                                attribute = factory.createAttribute(request,
                                        cleanString(contents[0]), contents[i]);
                            } catch (NullPointerException e) {
                                throw new ErrorDataDecoderException(e);
                            } catch (IllegalArgumentException e) {
                                throw new ErrorDataDecoderException(e);
                            }
                            currentFieldAttributes.put(attribute.getName(), attribute);
                        }
                    }
                }
            }
        }
        // Is it a FileUpload
        Attribute filenameAttribute = currentFieldAttributes.get(HttpHeaderValues.FILENAME);
        if (currentStatus == MultiPartStatus.DISPOSITION) {
            if (filenameAttribute != null) {
                // FileUpload
                currentStatus = MultiPartStatus.FILEUPLOAD;
                // do not change the buffer position
                return decodeMultipart(MultiPartStatus.FILEUPLOAD);
            } else {
                // Field
                currentStatus = MultiPartStatus.FIELD;
                // do not change the buffer position
                return decodeMultipart(MultiPartStatus.FIELD);
            }
        } else {
            if (filenameAttribute != null) {
                // FileUpload
                currentStatus = MultiPartStatus.MIXEDFILEUPLOAD;
                // do not change the buffer position
                return decodeMultipart(MultiPartStatus.MIXEDFILEUPLOAD);
            } else {
                // Field is not supported in MIXED mode
                throw new ErrorDataDecoderException("Filename not found");
            }
        }
    }

    private static final String FILENAME_ENCODED = HttpHeaderValues.FILENAME.toString() + '*';

    private Attribute getContentDispositionAttribute(String... values) {
        String name = cleanString(values[0]);
        String value = values[1];

        // Filename can be token, quoted or encoded. See https://tools.ietf.org/html/rfc5987
        if (HttpHeaderValues.FILENAME.contentEquals(name)) {
            // Value is quoted or token. Strip if quoted:
            int last = value.length() - 1;
            if (last > 0 &&
              value.charAt(0) == HttpConstants.DOUBLE_QUOTE &&
              value.charAt(last) == HttpConstants.DOUBLE_QUOTE) {
                value = value.substring(1, last);
            }
        } else if (FILENAME_ENCODED.equals(name)) {
            try {
                name = HttpHeaderValues.FILENAME.toString();
                String[] split = cleanString(value).split("'", 3);
                value = QueryStringDecoder.decodeComponent(split[2], Charset.forName(split[0]));
            } catch (ArrayIndexOutOfBoundsException e) {
                 throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        } else {
            // otherwise we need to clean the value
            value = cleanString(value);
        }
        return factory.createAttribute(request, name, value);
    }

    /**
     * Get the FileUpload (new one or current one)
     *
     * @param delimiter
     *            the delimiter to use
     * @return the InterfaceHttpData if any
     * @throws ErrorDataDecoderException
     */
    protected InterfaceHttpData getFileUpload(String delimiter) {
        // eventually restart from existing FileUpload
        // Now get value according to Content-Type and Charset
        Attribute encoding = currentFieldAttributes.get(HttpHeaderNames.CONTENT_TRANSFER_ENCODING);
        Charset localCharset = charset;
        // Default
        TransferEncodingMechanism mechanism = TransferEncodingMechanism.BIT7;
        if (encoding != null) {
            String code;
            try {
                code = encoding.getValue().toLowerCase();
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            }
            if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BIT7.value())) {
                localCharset = CharsetUtil.US_ASCII;
            } else if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BIT8.value())) {
                localCharset = CharsetUtil.ISO_8859_1;
                mechanism = TransferEncodingMechanism.BIT8;
            } else if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BINARY.value())) {
                // no real charset, so let the default
                mechanism = TransferEncodingMechanism.BINARY;
            } else {
                throw new ErrorDataDecoderException("TransferEncoding Unknown: " + code);
            }
        }
        Attribute charsetAttribute = currentFieldAttributes.get(HttpHeaderValues.CHARSET);
        if (charsetAttribute != null) {
            try {
                localCharset = Charset.forName(charsetAttribute.getValue());
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        }
        if (currentFileUpload == null) {
            Attribute filenameAttribute = currentFieldAttributes.get(HttpHeaderValues.FILENAME);
            Attribute nameAttribute = currentFieldAttributes.get(HttpHeaderValues.NAME);
            Attribute contentTypeAttribute = currentFieldAttributes.get(HttpHeaderNames.CONTENT_TYPE);
            Attribute lengthAttribute = currentFieldAttributes.get(HttpHeaderNames.CONTENT_LENGTH);
            long size;
            try {
                size = lengthAttribute != null ? Long.parseLong(lengthAttribute.getValue()) : 0L;
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            } catch (NumberFormatException ignored) {
                size = 0;
            }
            try {
                String contentType;
                if (contentTypeAttribute != null) {
                    contentType = contentTypeAttribute.getValue();
                } else {
                    contentType = HttpPostBodyUtil.DEFAULT_BINARY_CONTENT_TYPE;
                }
                currentFileUpload = factory.createFileUpload(request,
                        cleanString(nameAttribute.getValue()), cleanString(filenameAttribute.getValue()),
                        contentType, mechanism.value(), localCharset,
                        size);
            } catch (NullPointerException e) {
                throw new ErrorDataDecoderException(e);
            } catch (IllegalArgumentException e) {
                throw new ErrorDataDecoderException(e);
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            }
        }
        // load data as much as possible
        if (!loadDataMultipart(undecodedChunk, delimiter, currentFileUpload)) {
            // Delimiter is not found. Need more chunks.
            return null;
        }
        if (currentFileUpload.isCompleted()) {
            // ready to load the next one
            if (currentStatus == MultiPartStatus.FILEUPLOAD) {
                currentStatus = MultiPartStatus.HEADERDELIMITER;
                currentFieldAttributes = null;
            } else {
                currentStatus = MultiPartStatus.MIXEDDELIMITER;
                cleanMixedAttributes();
            }
            FileUpload fileUpload = currentFileUpload;
            currentFileUpload = null;
            return fileUpload;
        }
        // do not change the buffer position
        // since some can be already saved into FileUpload
        // So do not change the currentStatus
        return null;
    }

    /**
     * Destroy the {@link HttpPostMultipartRequestDecoder} and release all it resources. After this method
     * was called it is not possible to operate on it anymore.
     */
    @Override
    public void destroy() {
        // Release all data items, including those not yet pulled
        cleanFiles();

        destroyed = true;

        if (undecodedChunk != null && undecodedChunk.refCnt() > 0) {
            undecodedChunk.release();
            undecodedChunk = null;
        }
    }

    /**
     * Clean all HttpDatas (on Disk) for the current request.
     */
    @Override
    public void cleanFiles() {
        checkDestroyed();

        factory.cleanRequestHttpData(request);
    }

    /**
     * Remove the given FileUpload from the list of FileUploads to clean
     */
    @Override
    public void removeHttpDataFromClean(InterfaceHttpData data) {
        checkDestroyed();

        factory.removeHttpDataFromClean(request, data);
    }

    /**
     * Remove all Attributes that should be cleaned between two FileUpload in
     * Mixed mode
     */
    private void cleanMixedAttributes() {
        currentFieldAttributes.remove(HttpHeaderValues.CHARSET);
        currentFieldAttributes.remove(HttpHeaderNames.CONTENT_LENGTH);
        currentFieldAttributes.remove(HttpHeaderNames.CONTENT_TRANSFER_ENCODING);
        currentFieldAttributes.remove(HttpHeaderNames.CONTENT_TYPE);
        currentFieldAttributes.remove(HttpHeaderValues.FILENAME);
    }

    /**
     * Read one line up to the CRLF or LF
     *
     * @return the String from one line
     * @throws NotEnoughDataDecoderException
     *             Need more chunks and reset the {@code readerIndex} to the previous
     *             value
     */
    private static String readLine(ByteBuf undecodedChunk, Charset charset) {
        final int readerIndex = undecodedChunk.readerIndex();
        int posLf = undecodedChunk.bytesBefore(HttpConstants.LF);
        if (posLf == -1) {
            throw new NotEnoughDataDecoderException();
        }
        boolean crFound =
            undecodedChunk.getByte(readerIndex + posLf - 1) == HttpConstants.CR;
        if (crFound) {
            posLf--;
        }
        CharSequence line = undecodedChunk.readCharSequence(posLf, charset);
        if (crFound) {
            undecodedChunk.skipBytes(2);
        } else {
            undecodedChunk.skipBytes(1);
        }
        return line.toString();
    }

    /**
     * Read one line up to --delimiter or --delimiter-- and if existing the CRLF
     * or LF Read one line up to --delimiter or --delimiter-- and if existing
     * the CRLF or LF. Note that CRLF or LF are mandatory for opening delimiter
     * (--delimiter) but not for closing delimiter (--delimiter--) since some
     * clients does not include CRLF in this case.
     *
     * @param delimiter
     *            of the form --string, such that '--' is already included
     * @return the String from one line as the delimiter searched (opening or
     *         closing)
     * @throws NotEnoughDataDecoderException
     *             Need more chunks and reset the {@code readerIndex} to the previous
     *             value
     */
    private String readDelimiter(ByteBuf undecodedChunk, String delimiter) {
        final int readerIndex = undecodedChunk.readerIndex();
        try {
            final int len = delimiter.length();
            if (len + 2 > undecodedChunk.readableBytes()) {
                // Not able to check if "--" is present
                throw new NotEnoughDataDecoderException();
            }
            int newPositionDelimiter = findDelimiter(undecodedChunk, delimiter, 0);
            if (newPositionDelimiter != 0) {
                // Delimiter not fully found
                throw new NotEnoughDataDecoderException();
            }
            byte nextByte = undecodedChunk.getByte(readerIndex + len);
            // first check for opening delimiter
            if (nextByte == HttpConstants.CR) {
                nextByte = undecodedChunk.getByte(readerIndex + len + 1);
                if (nextByte == HttpConstants.LF) {
                    CharSequence line = undecodedChunk.readCharSequence(len, charset);
                    undecodedChunk.skipBytes(2);
                    return line.toString();
                } else {
                    // error since CR must be followed by LF
                    // delimiter not found so break here !
                    undecodedChunk.readerIndex(readerIndex);
                    throw new NotEnoughDataDecoderException();
                }
            } else if (nextByte == HttpConstants.LF) {
                CharSequence line = undecodedChunk.readCharSequence(len, charset);
                undecodedChunk.skipBytes(1);
                return line.toString();
            } else if (nextByte == '-') {
                // second check for closing delimiter
                nextByte = undecodedChunk.getByte(readerIndex + len + 1);
                if (nextByte == '-') {
                    CharSequence line = undecodedChunk.readCharSequence(len + 2, charset);
                    // now try to find if CRLF or LF there
                    if (undecodedChunk.isReadable()) {
                        nextByte = undecodedChunk.readByte();
                        if (nextByte == HttpConstants.CR) {
                            nextByte = undecodedChunk.readByte();
                            if (nextByte == HttpConstants.LF) {
                                return line.toString();
                            } else {
                                // error CR without LF
                                // delimiter not found so break here !
                                undecodedChunk.readerIndex(readerIndex);
                                throw new NotEnoughDataDecoderException();
                            }
                        } else if (nextByte == HttpConstants.LF) {
                            return line.toString();
                        } else {
                            // No CRLF but ok however (Adobe Flash uploader)
                            // minus 1 since we read one char ahead but
                            // should not
                            undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
                            return line.toString();
                        }
                    }
                    // FIXME what do we do here?
                    // either considering it is fine, either waiting for
                    // more data to come?
                    // lets try considering it is fine...
                    return line.toString();
                }
                // only one '-' => not enough
                // whatever now => error since incomplete
            }
        } catch (IndexOutOfBoundsException e) {
            undecodedChunk.readerIndex(readerIndex);
            throw new NotEnoughDataDecoderException(e);
        }
        undecodedChunk.readerIndex(readerIndex);
        throw new NotEnoughDataDecoderException();
    }

    /**
     * @param undecodedChunk the source where the delimiter is to be found
     * @param delimiter the string to find out
     * @param offset the offset from readerIndex within the undecodedChunk to
     *     start from to find out the delimiter
     *
     * @return a number >= 0 if found, else new offset with negative value
     *     (to inverse), both from readerIndex
     * @throws NotEnoughDataDecoderException
     *             Need more chunks while relative position with readerIndex is 0
     */
    private static int findDelimiter(ByteBuf undecodedChunk, String delimiter, int offset) {
        final int startReaderIndex = undecodedChunk.readerIndex();
        final int delimeterLength = delimiter.length();
        final int toRead = undecodedChunk.readableBytes();
        int newOffset = offset;
        boolean delimiterNotFound = true;
        while (delimiterNotFound && newOffset + delimeterLength <= toRead) {
            int posFirstChar = undecodedChunk
                .bytesBefore(startReaderIndex + newOffset, toRead - newOffset,
                             (byte) delimiter.codePointAt(0));
            if (posFirstChar == -1) {
                newOffset = toRead;
                return -newOffset;
            }
            newOffset = posFirstChar + newOffset;
            if (newOffset + delimeterLength > toRead) {
                return -newOffset;
            }
            // assume will found it
            delimiterNotFound = false;
            for (int index = 1; index < delimeterLength; index++) {
                if (undecodedChunk.getByte(startReaderIndex + newOffset + index) != delimiter.codePointAt(index)) {
                    // ignore first found offset and redo search from next char
                    newOffset++;
                    delimiterNotFound = true;
                    break;
                }
            }
        }
        if (delimiterNotFound || newOffset + delimeterLength > toRead) {
            if (newOffset == 0) {
                throw new NotEnoughDataDecoderException();
            }
            return -newOffset;
        }
        return newOffset;
    }

    /**
     * Load the field value from a Multipart request
     *
     * @return {@code true} if the last chunk is loaded (boundary delimiter found), {@code false} if need more chunks
     *
     * @throws ErrorDataDecoderException
     */
    private boolean loadDataMultipart(ByteBuf undecodedChunk, String delimiter,
                                      HttpData httpData) {
        final int startReaderIndex = undecodedChunk.readerIndex();
        int newOffset;
        try {
            newOffset = findDelimiter(undecodedChunk, delimiter, lastDataPosition);
            if (newOffset < 0) {
                // delimiter not found
                lastDataPosition = -newOffset;
                return false;
            }
        } catch (NotEnoughDataDecoderException e) {
            // Not enough data and no change to lastDataPosition
            return false;
        }
        // found delimiter but still need to check if CRLF before
        int startDelimiter = newOffset;
        if (undecodedChunk.getByte(startReaderIndex + startDelimiter - 1) == HttpConstants.LF) {
            startDelimiter--;
            if (undecodedChunk.getByte(startReaderIndex + startDelimiter - 1) == HttpConstants.CR) {
                startDelimiter--;
            }
        }
        ByteBuf content = undecodedChunk.retainedSlice(startReaderIndex, startDelimiter);
        try {
            httpData.addContent(content, true);
        } catch (IOException e) {
            throw new ErrorDataDecoderException(e);
        }
        lastDataPosition = 0;
        undecodedChunk.readerIndex(startReaderIndex + startDelimiter);
        return true;
    }

    /**
     * Clean the String from any unallowed character
     *
     * @return the cleaned String
     */
    private static String cleanString(String field) {
        int size = field.length();
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            char nextChar = field.charAt(i);
            switch (nextChar) {
            case HttpConstants.COLON:
            case HttpConstants.COMMA:
            case HttpConstants.EQUALS:
            case HttpConstants.SEMICOLON:
            case HttpConstants.HT:
                sb.append(HttpConstants.SP_CHAR);
                break;
            case HttpConstants.DOUBLE_QUOTE:
                // nothing added, just removes it
                break;
            default:
                sb.append(nextChar);
                break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Skip one empty line
     *
     * @return True if one empty line was skipped
     */
    private boolean skipOneLine() {
        if (!undecodedChunk.isReadable()) {
            return false;
        }
        byte nextByte = undecodedChunk.readByte();
        if (nextByte == HttpConstants.CR) {
            if (!undecodedChunk.isReadable()) {
                undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
                return false;
            }
            nextByte = undecodedChunk.readByte();
            if (nextByte == HttpConstants.LF) {
                return true;
            }
            undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 2);
            return false;
        }
        if (nextByte == HttpConstants.LF) {
            return true;
        }
        undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
        return false;
    }

    /**
     * Split one header in Multipart
     *
     * @return an array of String where rank 0 is the name of the header,
     *         follows by several values that were separated by ';' or ','
     */
    private static String[] splitMultipartHeader(String sb) {
        ArrayList<String> headers = new ArrayList<String>(1);
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;
        nameStart = HttpPostBodyUtil.findNonWhitespace(sb, 0);
        for (nameEnd = nameStart; nameEnd < sb.length(); nameEnd++) {
            char ch = sb.charAt(nameEnd);
            if (ch == ':' || Character.isWhitespace(ch)) {
                break;
            }
        }
        for (colonEnd = nameEnd; colonEnd < sb.length(); colonEnd++) {
            if (sb.charAt(colonEnd) == ':') {
                colonEnd++;
                break;
            }
        }
        valueStart = HttpPostBodyUtil.findNonWhitespace(sb, colonEnd);
        valueEnd = HttpPostBodyUtil.findEndOfString(sb);
        headers.add(sb.substring(nameStart, nameEnd));
        String svalue = (valueStart >= valueEnd) ? StringUtil.EMPTY_STRING : sb.substring(valueStart, valueEnd);
        String[] values;
        if (svalue.indexOf(';') >= 0) {
            values = splitMultipartHeaderValues(svalue);
        } else {
            values = svalue.split(",");
        }
        for (String value : values) {
            headers.add(value.trim());
        }
        String[] array = new String[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            array[i] = headers.get(i);
        }
        return array;
    }

    /**
     * Split one header value in Multipart
     * @return an array of String where values that were separated by ';' or ','
     */
    private static String[] splitMultipartHeaderValues(String svalue) {
        List<String> values = InternalThreadLocalMap.get().arrayList(1);
        boolean inQuote = false;
        boolean escapeNext = false;
        int start = 0;
        for (int i = 0; i < svalue.length(); i++) {
            char c = svalue.charAt(i);
            if (inQuote) {
                if (escapeNext) {
                    escapeNext = false;
                } else {
                    if (c == '\\') {
                        escapeNext = true;
                    } else if (c == '"') {
                        inQuote = false;
                    }
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ';') {
                    values.add(svalue.substring(start, i));
                    start = i + 1;
                }
            }
        }
        values.add(svalue.substring(start));
        return values.toArray(new String[0]);
    }
}
