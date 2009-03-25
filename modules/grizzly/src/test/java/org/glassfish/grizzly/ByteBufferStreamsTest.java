/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2003-2008 Sun Microsystems, Inc. All rights reserved.
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
 */
package org.glassfish.grizzly;

import java.util.List;
import java.util.ArrayList;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.Assert;
import junit.framework.TestCase;
import org.glassfish.grizzly.filterchain.FilterAdapter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.StopAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.ByteBufferWrapper;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.streams.StreamWriter;
import org.glassfish.grizzly.memory.slab.SlabMemoryManagerFactory;
import org.glassfish.grizzly.streams.AbstractStreamReader;
import org.glassfish.grizzly.util.LinkedTransferQueue;
import org.glassfish.grizzly.util.conditions.Condition;

/**
 * Basic idea:
 *   1. Set up a Grizzly client server connection.
 *   2. Create a Writer and write everything to it (using Checkers).
 *   3. Create a Reader out of the buffers generated by the writer.
 *   4. Check that the data that was written is what's received.
 *      This is done by having the client adding the checkers to a queue.
 *      The server then pops these checkers off the queue and calls their
 *      readAndCheck methods.
 *
 *    @author Ken Cavanaugh
 *    @author John Vieten
 **/
public class ByteBufferStreamsTest extends TestCase {

    public static final int PORT = 7778;
    private static Logger logger = Grizzly.logger;
    private final CountDownLatch poisonLatch = new CountDownLatch(1);
    private Connection clientconnection = null;
    private TCPNIOTransport servertransport = null;
    private StreamWriter clientWriter = null;
    private final Queue<Checker> checkerQueue = new LinkedTransferQueue();
    private TCPNIOTransport clienttransport;

    interface Checker {

        void write(StreamWriter writer) throws IOException;

        void readAndCheck(StreamReader reader) throws IOException;

        long operations();

        long byteSize();
    }

    abstract static class CheckerBase implements Checker {

        private String name;

        public CheckerBase() {
            String className = this.getClass().getName();
            int index = className.indexOf('$');
            name = className.substring(index + 1);
        }

        public abstract String value();

        @Override
        public String toString() {
            return name + "[" + value() + "]";
        }

        protected void werrMsg(Object obj, Throwable thr) {
            logger.log(Level.SEVERE,
                    "###Checker(" + toString() + ").write: Caught "
                    + thr + " at parameter " + obj);
        }

        protected void rerrMsg(Object obj, Throwable thr) {
            logger.log(Level.SEVERE, "###Checker(" + toString()
                    + ").readAndCheck: Caught " + thr + " at parameter " + obj);
        }

        public void wmsg() {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.SEVERE, "Write:" + toString());
            }
        }

        public void rmsg() {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.SEVERE, "ReadAndCheck:" + toString());
            }
        }

        public long operations() {
            return 1;
        }
    }

    static class CompositeChecker extends CheckerBase {

        private List<Checker> checkers;

        public String value() {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Checker ch : checkers) {
                if (first) {
                    first = false;
                } else {
                    sb.append(' ');
                }

                sb.append(ch.toString());
            }

            return sb.toString();
        }

        public CompositeChecker(Checker... args) {
            checkers = new ArrayList<Checker>();
            for (Checker ch : args) {
                checkers.add(ch);
            }
        }

        public void add(Checker ch) {
            checkers.add(ch);
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            Checker ch = null;

            try {
                for (Checker checker : checkers) {
                    ch = checker;
                    ch.write(writer);
                }
            } catch (Error err) {
                werrMsg(ch, err);
                throw err;
            } catch (RuntimeException exc) {
                werrMsg(ch, exc);
                throw exc;
            }
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            Checker ch = null;

            try {
                for (Checker checker : checkers) {
                    ch = checker;
                    ch.readAndCheck(reader);
                }
            } catch (Error err) {
                rerrMsg(ch, err);
                throw err;
            } catch (RuntimeException exc) {
                rerrMsg(ch, exc);
                throw exc;
            }
        }

        @Override
        public long operations() {
            long sum = 0;
            for (Checker ch : checkers) {
                sum += ch.operations();
            }

            return sum;
        }

        public long byteSize() {
            long sum = 0;
            for (Checker ch : checkers) {
                sum += ch.byteSize();
            }

            return sum;
        }
    }

    static class RepeatedChecker extends CheckerBase {

        private int count;
        private Checker checker;

        public String value() {
            return count + " " + checker.toString();
        }

        public RepeatedChecker(int count, Checker checker) {
            this.count = count;
            this.checker = checker;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            int ctr = 0;

            try {
                for (ctr = 0; ctr < count; ctr++) {
                    checker.write(writer);
                }
            } catch (Error err) {
                werrMsg(ctr, err);
                throw err;
            } catch (RuntimeException exc) {
                werrMsg(ctr, exc);
                throw exc;
            }
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            int ctr = 0;

            try {
                for (ctr = 0; ctr < count; ctr++) {
                    checker.readAndCheck(reader);
                }
            } catch (Error err) {
                rerrMsg(ctr, err);
                throw err;
            } catch (RuntimeException exc) {
                rerrMsg(ctr, exc);
                throw exc;
            }
        }

        @Override
        public long operations() {
            return count * checker.operations();
        }

        public long byteSize() {
            return count * checker.byteSize();
        }
    }

    static class ByteChecker extends CheckerBase {

        private byte data;

        public String value() {
            return "" + data;
        }

        public ByteChecker(byte arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeByte(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            byte value = reader.readByte();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 1;
        }
    }

    static class BooleanChecker extends CheckerBase {

        private boolean data;

        public String value() {
            return "" + data;
        }

        public BooleanChecker(boolean arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeBoolean(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            boolean value = reader.readBoolean();
            Assert.assertTrue(value == data);
        }

        public long byteSize() {
            return 1;
        }
    }

    static class CharChecker extends CheckerBase {

        private char data;

        public String value() {
            return "" + data;
        }

        public CharChecker(char arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeChar(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            char value = reader.readChar();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 2;
        }
    }

    static class ShortChecker extends CheckerBase {

        private short data;

        public String value() {
            return "" + data;
        }

        public ShortChecker(short arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeShort(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            short value = reader.readShort();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 2;
        }
    }

    static class IntChecker extends CheckerBase {

        private int data;

        public String value() {
            return "" + data;
        }

        public IntChecker(int arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeInt(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            int value = reader.readInt();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 4;
        }
    }

    static class LongChecker extends CheckerBase {

        private long data;

        public String value() {
            return "" + data;
        }

        public LongChecker(long arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeLong(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            long value = reader.readLong();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 8;
        }
    }

    static class FloatChecker extends CheckerBase {

        private float data;

        public String value() {
            return "" + data;
        }

        public FloatChecker(float arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeFloat(data);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            float value = reader.readFloat();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 4;
        }
    }

    static class DoubleChecker extends CheckerBase {

        private double data;

        public String value() {
            return "" + data;
        }

        public DoubleChecker(double arg) {
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeDouble(data);

        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            double value = reader.readDouble();
            Assert.assertEquals(value, data);
        }

        public long byteSize() {
            return 8;
        }
    }

    static class BooleanArrayChecker extends CheckerBase {

        private boolean data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public BooleanArrayChecker(int size, boolean arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            boolean[] value = new boolean[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeBooleanArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            boolean[] value = new boolean[size];
            reader.readBooleanArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertTrue(value[ctr] == data);
            }
        }

        public long byteSize() {
            return size;
        }
    }

    static class ByteArrayChecker extends CheckerBase {

        private byte data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public ByteArrayChecker(int size, byte arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            byte[] value = new byte[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeByteArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            byte[] value = new byte[size];
            reader.readByteArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return size;
        }
    }

    static class CharArrayChecker extends CheckerBase {

        private char data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public CharArrayChecker(int size, char arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            char[] value = new char[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeCharArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            char[] value = new char[size];
            reader.readCharArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return 2 * size;
        }
    }

    static class ShortArrayChecker extends CheckerBase {

        private short data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public ShortArrayChecker(int size, short arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            short[] value = new short[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeShortArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            short[] value = new short[size];
            reader.readShortArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return 2 * size;
        }
    }

    static class IntArrayChecker extends CheckerBase {

        private int data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public IntArrayChecker(int size, int arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            int[] value = new int[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeIntArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            int[] value = new int[size];
            reader.readIntArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return 4 * size;
        }
    }

    static class LongArrayChecker extends CheckerBase {

        private long data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public LongArrayChecker(int size, long arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            long[] value = new long[size];

            try {
                for (int ctr = 0; ctr < size; ctr++) {
                    value[ctr] = data;
                }
                writer.writeLongArray(value);
            } catch (Error err) {
                werrMsg(size, err);
                throw err;
            } catch (RuntimeException exc) {
                werrMsg(size, exc);
                throw exc;
            }
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            long[] value = new long[size];
            reader.readLongArray(value);

            try {
                for (int ctr = 0; ctr < size; ctr++) {
                    Assert.assertEquals(value[ctr], data);
                }
            } catch (Error err) {
                rerrMsg(size, err);
                throw err;
            } catch (RuntimeException exc) {
                rerrMsg(size, exc);
                throw exc;
            }
        }

        public long byteSize() {
            return 8 * size;
        }
    }

    static class FloatArrayChecker extends CheckerBase {

        private float data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public FloatArrayChecker(int size, float arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            float[] value = new float[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeFloatArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            float[] value = new float[size];
            reader.readFloatArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return 4 * size;
        }
    }

    static class DoubleArrayChecker extends CheckerBase {

        private double data;
        private int size;

        public String value() {
            return size + ":" + data;
        }

        public DoubleArrayChecker(int size, double arg) {
            this.size = size;
            data = arg;
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            double[] value = new double[size];
            for (int ctr = 0; ctr < size; ctr++) {
                value[ctr] = data;
            }
            writer.writeDoubleArray(value);
        }

        public void readAndCheck(StreamReader reader) throws IOException {
            rmsg();
            double[] value = new double[size];
            reader.readDoubleArray(value);
            for (int ctr = 0; ctr < size; ctr++) {
                Assert.assertEquals(value[ctr], data);
            }
        }

        public long byteSize() {
            return 8 * size;
        }
    }
    /**
     * Used to mark end of checking.
     *
     */
    static class PoisonChecker extends CheckerBase {

        private byte data = 1;

        public String value() {
            return "Poison";
        }

        public void write(StreamWriter writer) throws IOException {
            wmsg();
            writer.writeByte(data);
        }

        public void readAndCheck(StreamReader reader) {
        }

        public long byteSize() {
            return 1;
        }
    }


    // How best to construct checkers for running tests?
    //
    // use a series of private methods, each of which returns a Checker
    // comp( list )
    // rep( count, checker )
    // boolean      z
    // byte         b
    // char         c
    // short        s   
    // int          i
    // long         l
    // float        f
    // double       d
    // var octet    v
    // a for array
    private static Checker comp(Checker... arg) {
        return new CompositeChecker(arg);
    }

    private static Checker rep(int count, Checker ch) {
        return new RepeatedChecker(count, ch);
    }

    private static Checker z(boolean arg) {
        return new BooleanChecker(arg);
    }

    private static Checker b(byte arg) {
        return new ByteChecker(arg);
    }

    private static Checker c(char arg) {
        return new CharChecker(arg);
    }

    private static Checker s(short arg) {
        return new ShortChecker(arg);
    }

    private static Checker i(int arg) {
        return new IntChecker(arg);
    }

    private static Checker l(long arg) {
        return new LongChecker(arg);
    }

    private static Checker f(float arg) {
        return new FloatChecker(arg);
    }

    private static Checker d(double arg) {
        return new DoubleChecker(arg);
    }

    private static Checker za(int size, boolean arg) {
        return new BooleanArrayChecker(size, arg);
    }

    private static Checker ba(int size, byte arg) {
        return new ByteArrayChecker(size, arg);
    }

    private static Checker ca(int size, char arg) {
        return new CharArrayChecker(size, arg);
    }

    private static Checker sa(int size, short arg) {
        return new ShortArrayChecker(size, arg);
    }

    private static Checker ia(int size, int arg) {
        return new IntArrayChecker(size, arg);
    }

    private static Checker la(int size, long arg) {
        return new LongArrayChecker(size, arg);
    }

    private static Checker fa(int size, float arg) {
        return new FloatArrayChecker(size, arg);
    }

    private static Checker da(int size, double arg) {
        return new DoubleArrayChecker(size, arg);
    }

    public void testStreaming() throws IOException {
        // comment this test out until threading bug is found.
        //
     
        //testPrimitives
        int REPEAT_COUNT = 4;
        Checker ch =
                rep(REPEAT_COUNT, comp(z(true), b((byte) 46), c('A'),
                s((short) 2343), i(231231), l(-789789789789L),
                f((float) 123.23), d(2321.3232213)));
        send(ch);
        //testReaderWriter
        ch = rep(REPEAT_COUNT, comp(rep(10, l(23)), rep(20, l(32123)),
                rep(30, l(7483848374L)), rep(101, l(0))));

        send(ch);
       
        // testByte
        ch = rep(REPEAT_COUNT, b((byte) 23));
        send(ch);
        //testShort
        ch = rep(REPEAT_COUNT, s((short) 43));
        send(ch);
        //testInt
        ch = rep(REPEAT_COUNT, i(11));
        send(ch);
        //testLong
        ch = rep(REPEAT_COUNT, l(2378878));
        send(ch);
        ch = rep(REPEAT_COUNT, z(true));
        send(ch);
        ch = rep(REPEAT_COUNT, z(false));
        send(ch);
      

       ch = rep(REPEAT_COUNT, comp(
                la(913, 1234567879123456789L),
                ba(5531, (byte) 39),
                ca(5792, 'A'),
                sa(3324, (short) 27456),
                ia(479, 356789),
                la(4123, 49384892348923489L),
                fa(5321, (float) 12.7),
                da(2435, 45921.45891)));


        send(ch);
        // end
        send(new PoisonChecker());
        // test streaming
        MemoryManager alloc = SlabMemoryManagerFactory.makeAllocator(10000,false) ;
        byte[] testdata = new byte[500] ;


        for (int ctr=0; ctr<testdata.length; ctr++)
            testdata[ctr] = (byte)((ctr + 10) & 255) ;

        AbstractStreamReader reader=new AbstractStreamReader() {

            public Future notifyCondition(Condition condition, CompletionHandler completionHandler) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            protected Buffer read0() throws IOException {
                throw new UnsupportedOperationException("Not supported yet.");
            }

        };

        Buffer<ByteBuffer> buffer1 = alloc.allocate(125) ;
        buffer1.put(testdata, 0,125);
        Buffer<ByteBuffer> buffer2 = alloc.allocate(125) ;
        buffer2.put(testdata, 125,125);
        Buffer<ByteBuffer> buffer3 = alloc.allocate(125) ;
        buffer3.put(testdata, 250,125);
        Buffer<ByteBuffer> buffer4 = alloc.allocate(125) ;
        buffer4.put(testdata, 375,125);
        reader.appendBuffer((ByteBufferWrapper)buffer1.flip());
        reader.appendBuffer((ByteBufferWrapper)buffer2.flip());
        reader.appendBuffer((ByteBufferWrapper)buffer3.flip());
        reader.appendBuffer((ByteBufferWrapper)buffer4.flip());

        byte[] checkArray = new byte[500];
        try {
          reader.read(checkArray, 0, 500);
        }catch(Exception e) {
            logger.log(Level.SEVERE, "Data generate error",e);
        }

        Assert.assertEquals( true,Arrays.equals(checkArray, testdata) ) ;

        


    }

    @Override
    public void setUp() {
        setupServer();
        setupClient();

    }

    @Override
    public void tearDown() {

        try {

            poisonLatch.await(10, TimeUnit.SECONDS);
            //give filterchain some time to finisch
            synchronized(Thread.currentThread()) {
              Thread.currentThread().wait(100);
            }
            clientWriter.close();
            clientconnection.close();
            servertransport.stop();
            clienttransport.stop();
            TransportFactory.getInstance().close();

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Close", ex);
        }
    }

    public void setupServer() {
        servertransport = TransportFactory.getInstance().createTCPTransport();
        // no use for default memorymanager
//        servertransport.setMemoryManager(null);
        servertransport.getFilterChain().add(new TransportFilter());
        servertransport.getFilterChain().add(new FilterAdapter() {

            @Override
            public NextAction handleRead(FilterChainContext ctx,
                    NextAction nextAction) throws IOException {
                StreamReader reader = (StreamReader) ctx.getStreamReader();
                Checker checker;
                //must use a loop since StreamReader may contain data
                // of several checkers...
                while ((checker = checkerQueue.peek()) != null) {
                    if (checker instanceof PoisonChecker) {
                        poisonLatch.countDown();
                        return nextAction;
                    }

                    if (logger.isLoggable(Level.FINEST)) {
                        logger.log(Level.FINEST, "reader.availableDataSize():"
                                + reader.availableDataSize() + ","
                                + checker.byteSize());
                    }

                    Future f = reader.notifyAvailable((int) checker.byteSize());
                    try {
                        f.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        e.printStackTrace();
                        poisonLatch.countDown();
                    }

                    assertTrue(f.isDone());

                    if (reader.availableDataSize() < checker.byteSize()) {
                        return new StopAction();
                    }
                    checker.readAndCheck(reader);
                    checkerQueue.remove();
                }
                return nextAction;
            }
        });


        try {
            servertransport.bind(PORT);
            servertransport.configureBlocking(false);
            servertransport.start();


        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Server start error", ex);
        }

    }

    private void send(final Checker checker) throws IOException {
        checkerQueue.add(checker);
        checker.write(clientWriter);
        clientWriter.flush();
    }

    public void setupClient() {

        clienttransport = TransportFactory.getInstance().createTCPTransport();
        try {

            clienttransport.start();
            clienttransport.configureBlocking(false);
            Future<Connection> future =
                    clienttransport.connect("localhost", PORT);
            clientconnection = future.get(10, TimeUnit.SECONDS);
            assertTrue(clientconnection != null);


            clientWriter = clientconnection.getStreamWriter();
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Client start error", ex);
        }

    }
}


