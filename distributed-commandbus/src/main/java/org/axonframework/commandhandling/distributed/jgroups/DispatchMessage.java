/*
 * Copyright (c) 2010-2012. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.distributed.jgroups;

import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.domain.MetaData;
import org.axonframework.serializer.SerializedMetaData;
import org.axonframework.serializer.SerializedObject;
import org.axonframework.serializer.Serializer;
import org.axonframework.serializer.SimpleSerializedObject;
import org.jgroups.util.Streamable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * JGroups message that contains a CommandMessage that needs to be dispatched on a remote command bus segment. This
 * class implements the {@link Streamable} interface for faster JGroups-specific serialization, but also supports
 * Java serialization by implementing the {@link Externalizable} interface.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class DispatchMessage implements Streamable, Externalizable {

    private static final long serialVersionUID = -8792911964758889674L;

    private String commandIdentifier;
    private boolean expectReply;
    private String payloadType;
    private String payloadRevision;
    private byte[] serializedPayload;
    private byte[] serializedMetaData;

    /**
     * Default constructor required by the {@link Streamable} and {@link Externalizable} interfaces. Do not use
     * directly.
     */
    @SuppressWarnings("UnusedDeclaration")
    public DispatchMessage() {
    }

    /**
     * Initialized a DispatchMessage for the given <code>commandMessage</code>, to be serialized using given
     * <code>serializer</code>. <code>expectReply</code> indicates whether the sender will be expecting a reply.
     *
     * @param commandMessage The message to send to the remote segment
     * @param serializer     The serialize to serialize the message payload and metadata with
     * @param expectReply    whether or not the sender is waiting for a reply.
     */
    public DispatchMessage(CommandMessage<?> commandMessage, Serializer serializer, boolean expectReply) {
        this.commandIdentifier = commandMessage.getIdentifier();
        this.expectReply = expectReply;
        SerializedObject<byte[]> payload = serializer.serialize(commandMessage.getPayload(), byte[].class);
        SerializedObject<byte[]> metaData = serializer.serialize(commandMessage.getMetaData(), byte[].class);
        payloadType = payload.getType().getName();
        payloadRevision = payload.getType().getRevision();
        serializedPayload = payload.getData();
        serializedMetaData = metaData.getData();
    }

    /**
     * Indicates whether the sender of this message requests a reply.
     *
     * @return <code>true</code> if a reply is expected, otherwise <code>false</code>.
     */
    public boolean isExpectReply() {
        return expectReply;
    }

    /**
     * Returns the CommandMessage wrapped in this Message.
     *
     * @param serializer The serialize to deserialize message contents with
     * @return the CommandMessage wrapped in this Message
     */
    public CommandMessage<?> getCommandMessage(Serializer serializer) {
        final Object payload = serializer.deserialize(new SimpleSerializedObject<byte[]>(serializedPayload,
                                                                                         byte[].class,
                                                                                         payloadType,
                                                                                         payloadRevision));
        final MetaData metaData = (MetaData) serializer.deserialize(new SerializedMetaData<byte[]>(serializedMetaData,
                                                                                                   byte[].class));
        return new GenericCommandMessage<Object>(commandIdentifier, payload, metaData);
    }

    @Override
    public void writeTo(DataOutput out) throws IOException {
        out.writeUTF(commandIdentifier);
        out.writeBoolean(expectReply);
        out.writeUTF(payloadType);
        out.writeUTF(payloadRevision == null ? "_null" : payloadRevision);
        out.writeInt(serializedPayload.length);
        out.write(serializedPayload);
        out.writeInt(serializedMetaData.length);
        out.write(serializedMetaData);
    }

    @Override
    public void readFrom(DataInput in) throws IOException {
        commandIdentifier = in.readUTF();
        expectReply = in.readBoolean();
        payloadType = in.readUTF();
        payloadRevision = in.readUTF();
        if ("_null".equals(payloadRevision)) {
            payloadRevision = null;
        }
        serializedPayload = new byte[in.readInt()];
        in.readFully(serializedPayload);
        serializedMetaData = new byte[in.readInt()];
        in.readFully(serializedMetaData);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        writeTo(out);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException {
        readFrom(in);
    }
}
