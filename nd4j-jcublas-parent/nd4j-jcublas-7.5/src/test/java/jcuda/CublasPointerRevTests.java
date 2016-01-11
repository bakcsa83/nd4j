package jcuda;

import jcuda.jcublas.JCublas2;
import org.junit.Before;
import org.junit.Test;
import org.nd4j.linalg.api.blas.BlasBufferUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.DefaultRandom;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.CublasPointer;
import org.nd4j.linalg.jcublas.buffer.BaseCudaDataBuffer;
import org.nd4j.linalg.jcublas.buffer.allocation.PageableDirectBufferMemoryStrategy;
import org.nd4j.linalg.jcublas.buffer.allocation.PinnedMemoryStrategy;
import org.nd4j.linalg.jcublas.context.ContextHolder;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.linalg.ops.transforms.Transforms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * This set of very vasic tests will check for memory leaks in different allocation cases.
 *
 * 1. full array/buffer allocation
 * 2. view allocation
 *
 * On later stages, sparse allocations should be tested here as well. But for cuSparse that shouldn't be an issue, due to dense underlying CSR format.
 *
 * All this tests should be executed against both Pageable and Pinned MemoryStrategies.
 *
 * @author raver119@gmail.com
 */
public class CublasPointerRevTests {


    private static Logger log = LoggerFactory.getLogger(CublasPointerRevTests.class);

    @Before
    public void setUp() throws Exception {

    }

    /**
     * This test is most simple check for backend loader ever.
     *
     * @throws Exception
     */
    @Test
    public void testSetup() throws Exception {
            INDArray array = Nd4j.create(new double[]{1.0});
    }


    /**
     * This is primitive test for forced MemoryStrategies
     * @throws Exception
     */
    @Test
    public void testForcedMemoryStrategy() throws Exception {
        ContextHolder.getInstance().forceMemoryStrategyForThread(new PinnedMemoryStrategy());

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        ContextHolder.getInstance().forceMemoryStrategyForThread(new PageableDirectBufferMemoryStrategy());

        assertEquals("PageableDirectBufferMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        // explicit check for nullified forced strategy, this should get back to default memory strategy for crr
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

    }

    /**
     *
     */
    @Test
    public void testPageableMemoryRelease() throws Exception {
        // force current thread to use Pageable memory strategy
        ContextHolder.getInstance().forceMemoryStrategyForThread(new PageableDirectBufferMemoryStrategy());

        assertEquals("PageableDirectBufferMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray array1 = Nd4j.create(new float[]{1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f});
        INDArray array2 = Nd4j.create(new float[]{1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f});

        CudaContext ctx = CudaContext.getBlasContext();

        double[] ret = new double[1];
        ret[0] = 15.0d;
        Pointer result = Pointer.to(ret);

        CublasPointer xCPointer = new CublasPointer(array1,ctx);

        BaseCudaDataBuffer buffer1 = (BaseCudaDataBuffer) xCPointer.getBuffer();

        assertEquals(DataBuffer.AllocationMode.DIRECT, buffer1.allocationMode());
        assertTrue(buffer1.copied(Thread.currentThread().getName()));
        assertFalse(buffer1.isPersist());

        // we're pushing whole array to device, not a view; so buffer length should be equal to array length
        assertEquals(15, buffer1.length());

        long addr_buff1 = xCPointer.getDevicePointer().getNativePointer();

        CublasPointer yCPointer = new CublasPointer(array2,ctx);

        BaseCudaDataBuffer buffer2 = (BaseCudaDataBuffer) yCPointer.getBuffer();

        assertFalse(xCPointer.isResultPointer());
        assertFalse(xCPointer.isClosed());


        JCublas2.cublasDdot(
                ctx.getHandle(),
                array1.length(),
                xCPointer.getDevicePointer().withByteOffset(array1.offset() * array1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array1),
                yCPointer.getDevicePointer().withByteOffset(array2.offset() * array2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array2),
                result);
        ctx.syncOldStream();


        // in this test copyToHost is handled by JCublas, so there's no need for explicit copyToHost call
        ctx.finishBlasOperation();


        // check that result not equals to 0
        assertNotEquals(15.0d, ret[0], 0.0001d);

        // we emulate AutoCloseable by direct close() call
        // close call should fire freeDevicePointer
        // AND freeHost
        xCPointer.close();
        yCPointer.close();



        // here we check, if device pointer was released
        assertEquals(true, xCPointer.isClosed());
        assertEquals(true, yCPointer.isClosed());

        // now we should check, if host memory was released.
        // if not - freeHost() wasn't called for corresponding buffer and we have memory leak there
        assertTrue(buffer1.isFreed());

        assertTrue(buffer2.isFreed());
    }

    /**
     * This test addresses subsequent alloc/free for views within pageable memory.
     * + we also test same offset allocation within same thread (sequental access test only)
     *
     * @throws Exception
     */
    @Test
    public void testPageableMemoryReleaseSlicedSubsequently() throws Exception {
        // force current thread to use Pageable memory strategy
        ContextHolder.getInstance().forceMemoryStrategyForThread(new PageableDirectBufferMemoryStrategy());

        assertEquals("PageableDirectBufferMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray baseArray1 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());
        INDArray baseArray2 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());


        INDArray slice1 = baseArray1.slice(1);
        INDArray slice2 = baseArray2.slice(1);

        CudaContext ctx = CudaContext.getBlasContext();

        CublasPointer xCPointer = new CublasPointer(slice1,ctx);
        CublasPointer yCPointer = new CublasPointer(slice2,ctx);

        // at this moment we have 2 buffers allocated, and 2 pointers + offsets set up. time to add new slices to the equation

        INDArray slice3 = baseArray1.slice(3);

        // please note, slice(1) isn't a type. we're testing one more edge here: double offset allocation being used within same thread
        INDArray slice4 = baseArray2.slice(1);

        CublasPointer xDPointer = new CublasPointer(slice3, ctx);
        CublasPointer yDPointer = new CublasPointer(slice4, ctx);

        BaseCudaDataBuffer bufferSlice1 = (BaseCudaDataBuffer) xCPointer.getBuffer();
        BaseCudaDataBuffer bufferSlice2 = (BaseCudaDataBuffer) yCPointer.getBuffer();
        BaseCudaDataBuffer bufferSlice3 = (BaseCudaDataBuffer) xDPointer.getBuffer();
        BaseCudaDataBuffer bufferSlice4 = (BaseCudaDataBuffer) yDPointer.getBuffer();

        JCublas2.cublasSaxpy(
                ctx.getHandle(),
                slice1.length(),
                Pointer.to(new float[]{1.0f}),
                xCPointer.getDevicePointer().withByteOffset(slice1.offset() * slice1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice1),
                yCPointer.getDevicePointer().withByteOffset(slice2.offset() * slice2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice2));
        ctx.syncOldStream();

        // we have to copyback
        yCPointer.copyToHost();

        ctx.finishBlasOperation();

        // now we'll start closing pointers
        xCPointer.close();
        yCPointer.close();
        // at this point buffers should be NOT freed, since we have 2 more allocations
        assertFalse(bufferSlice1.isFreed());
        assertFalse(bufferSlice2.isFreed());
        assertFalse(bufferSlice3.isFreed());
        assertFalse(bufferSlice4.isFreed());

        ctx = CudaContext.getBlasContext();

        // at this moment we assume that yCPointer contains updated result, and we'll check it's equality to slice4
        assertEquals(slice2.getDouble(1), slice4.getDouble(1), 0.001);

        // now we'll fire axpy on slices 3 & 4
        JCublas2.cublasSaxpy(
                ctx.getHandle(),
                slice3.length(),
                Pointer.to(new float[]{1.0f}),
                xDPointer.getDevicePointer().withByteOffset(slice3.offset() * slice3.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice3),
                yDPointer.getDevicePointer().withByteOffset(slice4.offset() * slice4.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice4));
        ctx.syncOldStream();

        // copyback, once again
        yDPointer.copyToHost();

        ctx.finishBlasOperation();

        // once again, we check that memory is updated properly
        assertEquals(slice2.getDouble(1), slice4.getDouble(1), 0.001);


        // now we free slice4, and all buffers should be released now
        xDPointer.close();
        yDPointer.close();

        assertTrue(bufferSlice1.isFreed());
        assertTrue(bufferSlice2.isFreed());
        assertTrue(bufferSlice3.isFreed());
        assertTrue(bufferSlice4.isFreed());
    }


    /**
     * This test addresses subsequent alloc/free for views within pinned memory.
     * + we also test same offset allocation within same thread (sequental access test only)
     *
     * @throws Exception
     */
    @Test
    public void testPinnedMemoryReleaseSlicedSubsequently() throws Exception {
        // force current thread to use Pageable memory strategy
        ContextHolder.getInstance().forceMemoryStrategyForThread(new PinnedMemoryStrategy());

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray baseArray1 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());
        INDArray baseArray2 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());


        INDArray slice1 = baseArray1.slice(1);
        INDArray slice2 = baseArray2.slice(1);

        CudaContext ctx = CudaContext.getBlasContext();

        CublasPointer xCPointer = new CublasPointer(slice1,ctx);
        CublasPointer yCPointer = new CublasPointer(slice2,ctx);

        // at this moment we have 2 buffers allocated, and 2 pointers + offsets set up. time to add new slices to the equation


        INDArray slice3 = baseArray1.slice(3);

        // please note, slice(1) isn't a typo. we're testing one more edge here: double offset allocation being used within same thread
        INDArray slice4 = baseArray2.slice(1);

        CublasPointer xDPointer = new CublasPointer(slice3, ctx);
        CublasPointer yDPointer = new CublasPointer(slice4, ctx);

        BaseCudaDataBuffer bufferSlice1 = (BaseCudaDataBuffer) xCPointer.getBuffer();
        BaseCudaDataBuffer bufferSlice2 = (BaseCudaDataBuffer) yCPointer.getBuffer();

        BaseCudaDataBuffer bufferSlice3 = (BaseCudaDataBuffer) xDPointer.getBuffer();
        BaseCudaDataBuffer bufferSlice4 = (BaseCudaDataBuffer) yDPointer.getBuffer();


        JCublas2.cublasSaxpy(
                ctx.getHandle(),
                slice1.length(),
                Pointer.to(new float[]{1.0f}),
                xCPointer.getDevicePointer().withByteOffset(slice1.offset() * slice1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice1),
                yCPointer.getDevicePointer().withByteOffset(slice2.offset() * slice2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice2));
        ctx.syncOldStream();

        // we have to copyback
        yCPointer.copyToHost();

        ctx.finishBlasOperation();

        // at this moment we assume that yCPointer contains updated result, and we'll check it's equality to slice4
        assertEquals(slice2.getDouble(1), slice4.getDouble(1), 0.001);

        // now we'll start closing pointers
        xCPointer.close();
        yCPointer.close();

        // at this point buffers should be NOT freed, since we have 2 more allocations
        assertFalse(bufferSlice1.isFreed());
        assertFalse(bufferSlice2.isFreed());

        assertFalse(bufferSlice3.isFreed());
        assertFalse(bufferSlice4.isFreed());

        ctx = CudaContext.getBlasContext();



        // now we'll fire axpy on slices 3 & 4
        JCublas2.cublasSaxpy(
                ctx.getHandle(),
                slice3.length(),
                Pointer.to(new float[]{1.0f}),
                xDPointer.getDevicePointer().withByteOffset(slice3.offset() * slice3.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice3),
                yDPointer.getDevicePointer().withByteOffset(slice4.offset() * slice4.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice4));
        ctx.syncOldStream();

        assertFalse(xDPointer.isClosed());
        assertFalse(yDPointer.isClosed());

        // copyback, once again
        yDPointer.copyToHost();

        ctx.finishBlasOperation();

        // once again, we check that memory is updated properly
        assertEquals(slice2.getDouble(1), slice4.getDouble(1), 0.001);


        // now we free slice4, and all buffers should be released now
        xDPointer.close();
        yDPointer.close();

        assertTrue(bufferSlice1.isFreed());
        assertTrue(bufferSlice2.isFreed());
        assertTrue(bufferSlice3.isFreed());
        assertTrue(bufferSlice4.isFreed());
    }

    @Test
    public void testPinnedMemoryRelease() throws Exception {

        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray array1 = Nd4j.create(new float[]{1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f});
        INDArray array2 = Nd4j.create(new float[]{1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f});


        double dotWrapped = Nd4j.getBlasWrapper().level1().dot(array1.length(), 1, array1, array2);


        CudaContext ctx = CudaContext.getBlasContext();

        double[] ret = new double[1];
        Pointer result = Pointer.to(ret);

        CublasPointer xCPointer = new CublasPointer(array1,ctx);

        BaseCudaDataBuffer buffer = (BaseCudaDataBuffer) xCPointer.getBuffer();

        assertEquals(DataBuffer.AllocationMode.DIRECT, buffer.allocationMode());
        assertTrue(buffer.copied(Thread.currentThread().getName()));
        assertFalse(buffer.isPersist());

        // we're pushing whole array to device, not a view; so buffer length should be equal to array length
        assertEquals(15, buffer.length());

        long addr_buff1 = xCPointer.getDevicePointer().getNativePointer();

        CublasPointer yCPointer = new CublasPointer(array2,ctx);

        assertFalse(xCPointer.isResultPointer());
        assertFalse(xCPointer.isClosed());

        JCublas2.cublasDdot(
                ctx.getHandle(),
                array1.length(),
                xCPointer.getDevicePointer(),
                1,
                yCPointer.getDevicePointer(),
                1,
                result);
        ctx.syncOldStream();

        // in this test copyToHost is handled by JCublas, so there's no need for explicit copyToHost call
        ctx.finishBlasOperation();


        // we emulate AutoCloseable by direct close() call
        // close call should fire freeDevicePointer
        // AND freeHost
        xCPointer.close();
        yCPointer.close();

        // here we check, if device pointer was released
        assertEquals(true, xCPointer.isClosed());
        assertEquals(true, yCPointer.isClosed());

        // now we should check, if host memory was released.
        // if not - freeHost() wasn't called for corresponding buffer and we have memory leak there
        assertTrue(buffer.isFreed());

        // Please note: we do NOT test result pointer here,since we assume it's handled by JCuda

        System.out.println("Dot product: " + ret[0] + " Dot wrapped: " + dotWrapped);
    }


    /**
     * This test addresses memory management for result array passed from ND4j to JcuBlas
     *
     * @throws Exception
     */
    @Test
    public void testPinnedMemoryReleaseResult() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        CudaContext ctx = CudaContext.getBlasContext();

        INDArray array1 = Nd4j.create(new float[]{1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f});
        INDArray array2 = Nd4j.create(new float[]{1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f});

        CublasPointer xAPointer = new CublasPointer(array1,ctx);

        BaseCudaDataBuffer buffer1 = (BaseCudaDataBuffer) xAPointer.getBuffer();

        assertEquals(DataBuffer.AllocationMode.DIRECT, buffer1.allocationMode());
        assertTrue(buffer1.copied(Thread.currentThread().getName()));
        assertFalse(buffer1.isPersist());

        assertEquals(15, buffer1.length());


        assertFalse(xAPointer.isResultPointer());
        assertFalse(xAPointer.isClosed());

        CublasPointer xBPointer = new CublasPointer(array2,ctx);


        BaseCudaDataBuffer buffer2 = (BaseCudaDataBuffer) xAPointer.getBuffer();


        JCublas2.cublasDaxpy(
                ctx.getHandle(),
                array1.length(),
                Pointer.to(new double[]{1.0}),
                xAPointer.getDevicePointer().withByteOffset(array1.offset() * array1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array1),
                xBPointer.getDevicePointer().withByteOffset(array2.offset() * array2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array2));
        ctx.syncOldStream();

        //now, since we have result array, we call for explicit copyback
        double valBefore = array2.getDouble(0);

        xBPointer.copyToHost();

        // we don't care if the result is true here. All we want to know is: if memory was really updated after copyback
        double valAfter = array2.getDouble(0);
        System.out.println("Val0 before: [" + valBefore+ "], after: ["+ valAfter+"]");
        assertNotEquals(valBefore, valAfter, 0.01);

        ctx.finishBlasOperation();

        // we emulate AutoCloseable by direct close() call
        // close call should fire freeDevicePointer
        // AND freeHost
        xAPointer.close();
        xBPointer.close();

        // here we check, if device pointer was released
        assertEquals(true, xAPointer.isClosed());
        assertEquals(true, xBPointer.isClosed());

        // now we should check, if host memory was released.
        // if not - freeHost() wasn't called for corresponding buffer and we have memory leak there
        assertTrue(buffer1.isFreed());

        // we check result buffer
        assertTrue(buffer2.isFreed());


        /*
            so, at this moment we know the followint machine state:
            1. Both cuBlasPointers are closed
            2. Both underlying buffers are freed
        */
    }

    /**
     * This test addresses GPU memory allocation for sliced views retrieved from larger array/buffer
     *
     * @throws Exception
     */
    @Test
    public void testPinnedMemoryReleaseSliced() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray baseArray1 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());
        INDArray baseArray2 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());

        INDArray slice1 = baseArray1.slice(1);
        INDArray slice2 = baseArray2.slice(1);

        CudaContext ctx = CudaContext.getBlasContext();

        // We are NOT using try-with-resource here, hence we use explicit call to xAPointer.close() method, as exact emoulation of AutoCloseable behaviour
        CublasPointer xAPointer = new CublasPointer(slice1,ctx);


        BaseCudaDataBuffer buffer1 = (BaseCudaDataBuffer) xAPointer.getBuffer();

        assertEquals(DataBuffer.AllocationMode.DIRECT, buffer1.allocationMode());
        assertTrue(buffer1.copied(Thread.currentThread().getName()));
        assertFalse(buffer1.isPersist());

        // for sliced view we have whole original array allocated
        assertEquals(200000, buffer1.length());

        CublasPointer xBPointer = new CublasPointer(slice2,ctx);

        long addr_buff1 = xAPointer.getDevicePointer().getNativePointer();
        long addr_buff2 = xBPointer.getDevicePointer().getNativePointer();

        System.out.println("Native buffer1 pointer: " + addr_buff1);
        System.out.println("Native buffer2 pointer: " + addr_buff2);



        BaseCudaDataBuffer buffer2 = (BaseCudaDataBuffer) xBPointer.getBuffer();

        // the same here, for sliced view we have whole original buffer allocated using cudaHostAlloc
        assertEquals(200000, buffer2.length());


        JCublas2.cublasSaxpy(
                ctx.getHandle(),
                slice1.length(),
                Pointer.to(new float[]{1.0f}),
                xAPointer.getDevicePointer().withByteOffset(slice1.offset() * slice1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice1),
                xBPointer.getDevicePointer().withByteOffset(slice2.offset() * slice2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(slice2));
        ctx.syncOldStream();

        //now, since we have result array, we call for explicit copyback
        double valBefore = slice2.getFloat(0);

        xBPointer.copyToHost();

        // we don't care if the result is true here. All we want to know is: if memory was really updated after copyback
        double valAfter = slice2.getDouble(0);
        System.out.println("Val0 before: [" + valBefore+ "], after: ["+ valAfter+"]");
        assertNotEquals(valBefore, valAfter, 0.01);

        ctx.finishBlasOperation();


        // we emulate AutoCloseable by direct close() call
        // close() call should fire freeDevicePointer
        // AND freeHost
        xAPointer.close();
        xBPointer.close();

        // here we check, if device pointer was released
        assertEquals(true, xAPointer.isClosed());
        assertEquals(true, xBPointer.isClosed());

        // now we should check, if host memory was released.
        // if not - freeHost() wasn't called for corresponding buffer and we have memory leak there
        assertFalse(buffer1.isFreed());

        // we check if result buffer is freed too.
        assertFalse(buffer2.isFreed());

        /*
            As you can see, this test fails here - underlying buffer of size 200000 elements hasn't got cudaFreeHost call
            That happens due to logical flaw in BaseCudaDataBuffer.free() method.
            And since try-with-resource is nothing more then auto-call for close() method, overall idea is flawed by this delegation.

            From now on, this buffer will stay allocated until application is terminated, however all subsequent view allocations will return proper pointers to this buffer + offset.
         */


        /*
            Now we know, that array1 and array2 backing buffers were not freed, and they are still in allocated by cudaHostAlloc().
            And we'll try to allocate one more slice from the same buffers.
         */

        slice1 = baseArray1.slice(2);
        slice2 = baseArray2.slice(2);

        ctx = CudaContext.getBlasContext();

        /*
            Since our backing buffer allocated for original array1/array2 was NOT freed, we'll obtain offset pointers to the buffer allocated on previous step.
        */
        xAPointer = new CublasPointer(slice1,ctx);
        xBPointer = new CublasPointer(slice2,ctx);

        // at this point we should have equal mem pointers to underlying buffers
        long new_addr_buff1 = xAPointer.getDevicePointer().getNativePointer();
        long new_addr_buff2 = xBPointer.getDevicePointer().getNativePointer();


        assertEquals(addr_buff1, new_addr_buff1);
        assertEquals(addr_buff2, new_addr_buff2);

        xAPointer.close();
        xBPointer.close();

    }


    /**
     * This test makes sure that data is transferred host->device->host path properly.
     * To check that, we use pre-calculated dot product validation
     *
     * @throws Exception
     */
    @Test
    public void testPageableBlasCallValue() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(new PageableDirectBufferMemoryStrategy());

        assertEquals("PageableDirectBufferMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        CudaContext ctx = CudaContext.getBlasContext();

        INDArray array1 = Nd4j.create(new float[]{1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f});
        INDArray array2 = Nd4j.create(new float[]{1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f});


        double dotWrapped = Nd4j.getBlasWrapper().dot(array1, array2);

        CublasPointer xAPointer = new CublasPointer(array1,ctx);
        CublasPointer xBPointer = new CublasPointer(array2,ctx);

        float[] ret = new float[1];
        ret[0] = 0;
        Pointer result = Pointer.to(ret);


        JCublas2.cublasSdot(
                ctx.getHandle(),
                array1.length(),
                xAPointer.getDevicePointer().withByteOffset(array1.offset() * array1.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array1),
                xBPointer.getDevicePointer().withByteOffset(array2.offset() * array2.data().getElementSize()),
                BlasBufferUtil.getBlasStride(array2),
                result);
        ctx.syncOldStream();



        ctx.finishBlasOperation();


        double res = ret[0]; //  / (norm1.doubleValue() * norm2.doubleValue());

        System.out.println("Val before: [0], after: ["+ ret[0]+"], norm: [" + res +"], dotWrapped: [" + dotWrapped + "]");

        xAPointer.close();
        xBPointer.close();

        assertEquals(dotWrapped, res, 0.001d);

        // this test fails since i don't have proper answer on laptop, will add it from desktop later
        assertEquals(16.665000915527344, res, 0.001d);
    }

    /**
     * This test makes sure that data is transferred host->device->host path properly.
     * To check that, we use pre-calculated dot product validation
     *
     * @throws Exception
     */
    @Test
    public void tesPinnedBlasCallValue() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        CudaContext ctx = CudaContext.getBlasContext();

        INDArray array1 = Nd4j.create(new float[]{1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f, 1.01f});
        INDArray array2 = Nd4j.create(new float[]{1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f, 1.10f});


        double dotWrapped = Nd4j.getBlasWrapper().dot(array1, array2);

        CublasPointer xAPointer = new CublasPointer(array1,ctx);
        CublasPointer xBPointer = new CublasPointer(array2,ctx);

        float[] ret = new float[1];
        Pointer result = Pointer.to(ret);

        System.out.println("Offset: ["+ array1.offset()+"], stride: ["+ BlasBufferUtil.getBlasStride(array1)+"]");
        System.out.println("Offset: ["+ array2.offset()+"], stride: ["+ BlasBufferUtil.getBlasStride(array2)+"]");

        try {
            JCublas2.cublasSdot(
                    ctx.getHandle(),
                    array1.length(),
                    xAPointer.getDevicePointer().withByteOffset(array1.offset() * array1.data().getElementSize()),
                    BlasBufferUtil.getBlasStride(array1),
                    xBPointer.getDevicePointer().withByteOffset(array2.offset() * array2.data().getElementSize()),
                    BlasBufferUtil.getBlasStride(array2),
                    result);
            ctx.syncOldStream();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        xAPointer.close();
        xBPointer.close();

        ctx.finishBlasOperation();



        double res = ret[0]; //  / (norm1.doubleValue() * norm2.doubleValue());


        System.out.println("Val before: [0], after: ["+ ret[0]+"], norm: [" + res +"], wrapped: [" + dotWrapped + "]");
        assertEquals(dotWrapped, res, 0.001d);

        // this test fails since i don't have proper answer on laptop, will add it from desktop later
        assertEquals(16.665000915527344, res, 0.001d);
    }

    @Test
    public void testPinnedMemoryNestedAllocation1() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray baseArray1 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());
        INDArray baseArray2 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());

        INDArray slice1 = baseArray1.slice(1);
        INDArray slice2 = baseArray1.slice(1);

        CudaContext ctx = CudaContext.getBlasContext();

        CublasPointer xAPointer = new CublasPointer(slice1,ctx);
        CublasPointer xBPointer = new CublasPointer(slice2,ctx);


        BaseCudaDataBuffer buffer1 = (BaseCudaDataBuffer) xAPointer.getBuffer();
        BaseCudaDataBuffer buffer2 = (BaseCudaDataBuffer) xBPointer.getBuffer();

        xAPointer.close();
        assertFalse(buffer1.isFreed());
        assertFalse(buffer2.isFreed());


        xBPointer.close();

        assertTrue(buffer1.isFreed());
        assertTrue(buffer2.isFreed());
    }


    @Test
    public void testPinnedMemoryNestedAllocation2() throws Exception {
        // simple way to stop test if we're not on CUDA backend here
        assertEquals("JcublasLevel1", Nd4j.getBlasWrapper().level1().getClass().getSimpleName());

        // reset to default MemoryStrategy, most probable is Pinned
        ContextHolder.getInstance().forceMemoryStrategyForThread(null);

        assertEquals("PinnedMemoryStrategy", ContextHolder.getInstance().getMemoryStrategy().getClass().getSimpleName());

        INDArray baseArray1 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());
        INDArray baseArray2 = Nd4j.rand(new int[]{1000, 200}, -1.0, 1.0, new DefaultRandom());

        INDArray slice1 = baseArray1.slice(1);
        INDArray slice2 = baseArray1.slice(2);

        CudaContext ctx = CudaContext.getBlasContext();

        CublasPointer xAPointer = new CublasPointer(slice1,ctx);
        CublasPointer xBPointer = new CublasPointer(slice2,ctx);


        BaseCudaDataBuffer buffer1 = (BaseCudaDataBuffer) xAPointer.getBuffer();
        BaseCudaDataBuffer buffer2 = (BaseCudaDataBuffer) xBPointer.getBuffer();

        xAPointer.close();
        assertFalse(buffer1.isFreed());
        assertFalse(buffer2.isFreed());


        xBPointer.close();

        assertTrue(buffer1.isFreed());
        assertTrue(buffer2.isFreed());
    }
}
