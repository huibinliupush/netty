/*
 * Copyright 2020 The Netty Project
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
package io.netty.buffer;

import static io.netty.buffer.PoolThreadCache.*;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 *   LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 *   LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 *   sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 *                 isSubPage, log2DeltaLookup] tuples.
 *     index: Size class index.
 *     log2Group: Log of group base size (no deltas added).
 *     log2Delta: Log of delta to previous size class.
 *     nDelta: Delta multiplier.
 *     isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 *     isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 *     log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 *                      otherwise.
 * <p>
 *   nSubpages: Number of subpages size classes.
 *   nSizes: Number of size classes.
 *   nPSizes: Number of size classes that are multiples of pageSize.
 *
 *   smallMaxSizeIdx: Maximum small size class index.
 *
 *   lookupMaxClass: Maximum size class included in lookup table.
 *   log2NormalMinClass: Log of minimum normal size class.
 * <p>
 *   The first size class and spacing are 1 << LOG2_QUANTUM.
 *   Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 *
 *   size = 1 << log2Group + nDelta * (1 << log2Delta)
 *
 *   The first size class has an unusual encoding, because the size has to be
 *   split between group and delta*nDelta.
 *
 *   If pageShift = 13, sizeClasses looks like this:
 *
 *   (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 *   ( 0,     4,        4,         0,       no,             yes,        4)
 *   ( 1,     4,        4,         1,       no,             yes,        4)
 *   ( 2,     4,        4,         2,       no,             yes,        4)
 *   ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 *   ( 4,     6,        4,         1,       no,             yes,        4)
 *   ( 5,     6,        4,         2,       no,             yes,        4)
 *   ( 6,     6,        4,         3,       no,             yes,        4)
 *   ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 *   ( 8,     7,        5,         1,       no,             yes,        5)
 *   ( 9,     7,        5,         2,       no,             yes,        5)
 *   ( 10,    7,        5,         3,       no,             yes,        5)
 *   ( 11,    7,        5,         4,       no,             yes,        5)
 *   ...
 *   ...
 *   ( 72,    23,       21,        1,       yes,            no,        no)
 *   ( 73,    23,       21,        2,       yes,            no,        no)
 *   ( 74,    23,       21,        3,       yes,            no,        no)
 *   ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 *   ( 76,    24,       22,        1,       yes,            no,        no)
 */
final class SizeClasses implements SizeClassesMetric {
    // The first size class and spacing are 1 << LOG2_QUANTUM
    // 第一类内存规格的 base size 和增长间距 16
    static final int LOG2_QUANTUM = 4;
    // 每组内存规格类别中的个数，每组四个规格
    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    // lookup table 中最大的内存规格 4K
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;
    // 定义 sizeClasses 内存规格表中的列索引
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;
    // 8K
    final int pageSize;
    // 13
    final int pageShifts;
    // 4M
    final int chunkSize;
    final int directMemoryCacheAlignment;

    final int nSizes;
    final int nSubpages;
    final int nPSizes;
    final int lookupMaxSize;
    final int smallMaxSizeIdx;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx (28K)
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxClass (4K)
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxClass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        // 22 - 4 -2 + 1 = 17
        int group = log2(chunkSize) - LOG2_QUANTUM - LOG2_SIZE_CLASS_GROUP + 1;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        short[][] sizeClasses = new short[group << LOG2_SIZE_CLASS_GROUP][7];

        int normalMaxSize = -1;
        // sizeIndex
        int nSizes = 0;
        // 内存规格
        int size = 0;
        // 内存规格的 base size 第一种是 16
        int log2Group = LOG2_QUANTUM;
        // 内存规格组内，每种规格尺寸之间的间隔 16
        int log2Delta = LOG2_QUANTUM;
        // 4
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        //First small group, nDelta start at 0.
        //first size class is 1 << LOG2_QUANTUM
        // 初始化 small size 内存规格 group , 第一种内存规格为 16
        // 初始化 index [0 , 3] 范围内的 sizeClass
        for (int nDelta = 0; nDelta < ndeltaLimit; nDelta++, nSizes++) {
            // 生成某个内存规格的尺寸索引 sizeClass
            short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
            // nSizes 为该规格尺寸的 index
            sizeClasses[nSizes] = sizeClass;
            // 通过 sizeClass 计算该内存规格的 size ,然后将 size 向上对齐至 directMemoryCacheAlignment 的最小整数倍
            size = sizeOf(sizeClass, directMemoryCacheAlignment);
        }
        // 6 下一组 [4 , 7] 范围内的 sizeClass 的 baseSize 从 16 变为了 64
        // 4 + 2 = 6
        log2Group += LOG2_SIZE_CLASS_GROUP;

        //All remaining groups, nDelta start at 1.
        // 将一个 chunkSize 按照不同粒度进行切分
        // Netty 内存规格划分的核心：16， 32，48，64 ....... 4M
        for (; size < chunkSize; log2Group++, log2Delta++) {
            // 按照内存规格组 LOG2_SIZE_CLASS_GROUP （每组内存规格为 4 个）进行初始化 sizeClass
            for (int nDelta = 1; nDelta <= ndeltaLimit && size < chunkSize; nDelta++, nSizes++) {
                short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
                sizeClasses[nSizes] = sizeClass;
                size = normalMaxSize = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }

        // chunkSize must be normalMaxSize
        // normal 的内存规格最大尺寸为 chunkSize（4M）
        // 超过 4M 就是 Huge 内存规格，直接分配不进行池化管理
        assert chunkSize == normalMaxSize;

        int smallMaxSizeIdx = 0;
        int lookupMaxSize = 0;
        // 在 Netty 的内存规格中，有多少规格是 pagesize 的整数倍（page级的规格）
        int nPSizes = 0;
        // 在 Netty 的内存规格中，有多少规格是 Subpages 的规格
        int nSubpages = 0;
        // 初始化完 sizeClass 之后 nSizes 为 67 ，对应的内存规格为 4M
        for (int idx = 0; idx < nSizes; idx++) {
            short[] sz = sizeClasses[idx];
            // 只要 size 可以被 pagesize 整除，那么就属于 MultiPageSize
            if (sz[PAGESIZE_IDX] == yes) {
                nPSizes++;
            }
            // 只要 size 小于 32K 则为 Subpage 的规格
            if (sz[SUBPAGE_IDX] == yes) {
                nSubpages++;
                // small 内存规格中的最大尺寸 28K ，对应的 sizeIndex = 38
                smallMaxSizeIdx = idx;
            }
            // 内存规格小于等于 4K 的都属于 lookup size
            if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
                // 4K
                lookupMaxSize = sizeOf(sz, directMemoryCacheAlignment);
            }
        }
        // 38
        this.smallMaxSizeIdx = smallMaxSizeIdx;
        // 4086(4K)
        this.lookupMaxSize = lookupMaxSize;
        // 32
        this.nPSizes = nPSizes;
        // 39
        this.nSubpages = nSubpages;
        // 68
        this.nSizes = nSizes;
        // 8192(8K)
        this.pageSize = pageSize;
        // 13
        this.pageShifts = pageShifts;
        // 4M
        this.chunkSize = chunkSize;
        // 0
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        //generate lookup tables
        // sizeIndex 与 size 之间的映射
        this.sizeIdx2sizeTab = newIdx2SizeTab(sizeClasses, nSizes, directMemoryCacheAlignment);
        // 根据 sizeClass 生成 page 级的内存规格表
        // pageIndex 到对应的 size 之间的映射
        this.pageIdx2sizeTab = newPageIdx2sizeTab(sizeClasses, nSizes, nPSizes, directMemoryCacheAlignment);
        // 4k 之内，给定一个 size 转换为 sizeIndex
        this.size2idxTab = newSize2idxTab(lookupMaxSize, sizeClasses);
    }

    //calculate size class
    private static short[] newSizeClass(int index, int log2Group, int log2Delta, int nDelta, int pageShifts) {
        // 是否能被 pagesize(8k)整除
        short isMultiPageSize;
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            // size = 1 << log2Group + nDelta * (1 << log2Delta)
            int size = calculateSize(log2Group, nDelta, log2Delta);

            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
        }

        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);
        // 该内存规格 size 是否是 lookup table 中的内存规格
        // 对于 4K 来说 log2Group = 11， log2Delta = 9，nDelta = 4，log2Ndelta = 2
        // 对于 5K 来说 log2Group = 12， log2Delta = 10，nDelta = 1，log2Ndelta = 0
        byte remove = 1 << log2Ndelta < nDelta? yes : no; // 只有 nDelta 为奇 3 的时候, remove 为 yes
        // 如果涨幅恰好是 base size 的一倍 ： log2Delta + log2Ndelta == log2Group
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;
        // 涨幅不是 base size 的一倍，不是组内最后一个规格
        if (log2Size == log2Group) {
            remove = yes;
        }
        // remove 为 no 的条件是：1. nDelta 不能是 3 。 2. 该规格的涨幅 log2Delta + log2Ndelta 必须是 log2Group（也就是说涨幅是 base size 的一倍）
        // 也就是 MAX_LOOKUP_SIZ 必须要是一个规格组的最后一个规格，否则 look up table 查找范围只能是小于 MAX_LOOKUP_SIZE 不能等于

        // size < 4倍的 pagesize （32k）就为 Subpage 的规格
        // 判断方式分为两种：对于组内前三个规格来说，只要它的 log2Group 不是，那就不是
        // 对于组内最后一个规格来说（log2Delta + log2Ndelta == log2Group），它的 size(log2Group + 1) 不是，那就不是
        short isSubpage = log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP? yes : no;

        // 如果内存规格 size 小于等于 MAX_LOOKUP_SIZE（4K），那么该项 lookup table 中的 log2DeltaLookup 为 log2Delta
        // 如果内存规格 size 大于 MAX_LOOKUP_SIZE（4K），则为 0
        // 表示 lookup table 中的内存规格与前一项规格的间隔
        // MAX_LOOKUP_SIZ 必须要是一个规格组的最后一个规格，否则 look up table 查找范围只能是小于 MAX_LOOKUP_SIZE 不能等于
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no
                ? log2Delta : no;

        return new short[] {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };
    }
    // sizeIndex 与 size 之间的映射
    private static int[] newIdx2SizeTab(short[][] sizeClasses, int nSizes, int directMemoryCacheAlignment) {
        int[] sizeIdx2sizeTab = new int[nSizes];

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            sizeIdx2sizeTab[i] = sizeOf(sizeClass, directMemoryCacheAlignment);
        }
        return sizeIdx2sizeTab;
    }
    // size = 1 << log2Group + nDelta * (1 << log2Delta)
    private static int calculateSize(int log2Group, int nDelta, int log2Delta) {
        return (1 << log2Group) + (nDelta << log2Delta);
    }

    private static int sizeOf(short[] sizeClass, int directMemoryCacheAlignment) {
        int log2Group = sizeClass[LOG2GROUP_IDX];
        int log2Delta = sizeClass[LOG2DELTA_IDX];
        int nDelta = sizeClass[NDELTA_IDX];
        // 根据 sizeClass 计算内存规格 size
        int size = calculateSize(log2Group, nDelta, log2Delta);
        // size 需要向上对齐至 directMemoryCacheAlignment 的最小整数倍
        return alignSizeIfNeeded(size, directMemoryCacheAlignment);
    }

    private static int[] newPageIdx2sizeTab(short[][] sizeClasses, int nSizes, int nPSizes,
                                            int directMemoryCacheAlignment) {
        // page 级的内存规格
        int[] pageIdx2sizeTab = new int[nPSizes];
        int pageIdx = 0;
        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }
        return pageIdx2sizeTab;
    }
    // 在 4K 以下的内存规格中，按照 16 的步长划分内存规格，这样做保证了如果我们分配 4k 以下的内存，最大的碎片也只能是 16 个字节
    private static int[] newSize2idxTab(int lookupMaxSize, short[][] sizeClasses) {
        // 4K 可以划分出多少个 16 为增长步长的内存规格
        int[] size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        int idx = 0;
        int size = 0;

        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            // log2Delta 表示前后内存规格之间的差值（sizeClass）,这个差值进一步按照 16 划分
            // 比如差值为 32 ，那么 times = 2
            // 比如说 sizeIndex = 7 的对应 size = 128,sizeIndex = 8 对应的 size = 160
            // sizeIndex = 8 的 Delta 为 32 ，但是还能进一步划分为两个规格 times = 2
            // size2idxTab 的 index 8 对应的 size 为 128 + 16 = 144 ，如果分配 size 为 144 那么 netty 就会对应分配 160(sizeIndex = 8)
            // size2idxTab 的 index 9 对应的 size 为 144 + 16 = 160,Netty 正好分配 160 (sizeIndex = 8)
            int times = 1 << log2Delta - LOG2_QUANTUM;

            while (size <= lookupMaxSize && times-- > 0) {
                // idx = 8(144) ,i = 8(160)
                // idx = 9(160) ,i = 8(160)
                // idx = 10(176) ,i = 9(192)
                // idx = 11(192) ,i = 9(192)
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
        // size - 1 >> LOG2_QUANTUM 为 index , 对应的值为 sizeIndex
        return size2idxTab;
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        // 获取该 size 属于哪一组的内存规格
        // 每组内存规格有四个（LOG2_SIZE_CLASS_GROUP），sizeIndex 除以 4 获取所属内存规格组
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        // 与 4 取余，获取具体的内存规格
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }
    // 如果 size 恰好夹在 sizeIdx2sizeTab 中的两个内存规格之间，那么取最大的规格
    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) {
            return nSizes;
        }

        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);
        // size 在 4K 以下直接去 lookup tabble 中去查
        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            // size2idxTab 中的查找 size 尺寸按照 16B 为间隔划分
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }
        // 每个内存规格组内最后一个规格都是 2 的次幂
        // size 向上取最接近 2 的次幂的内存规格，目的是获取 size 所在内存规格组内最后一个规格
        // 比如 5000 向上取 2 的次幂就是 8K，[5K , 8K] 同属 log2Group = 12 组
        int x = log2((size << 1) - 1);
        // x = 13 , shift = 7 , 表示 size 所在的是第 7 个内存规格组（除去第一个内存规格组（第 0 个内存规格组），它比较特殊 log2Group = 4）
        // 组内最后一个规格是 2 的次幂
        // 比如 第 0 组最后一个规格是 64 ，第 1 组是 128，第 2 组是 256，第 3 组是 512.它们都是 64 的倍数
        // x 是第 shift 组内最后一个规格，那么 shift 是第几组呢 ？ x - 6 也就是最后一个规格 size 除以 64 取商的对数
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);
        // size 在第 7 个规格组，每组 4 个规格，那么组内第一个规格的 index 就是 7 * 4 = 28
        int group = shift << LOG2_SIZE_CLASS_GROUP;
        // size 所在内存规格组内间隔
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        // 每组四个规格
        // size - 1 >> log2Delta & 3 计算出 size 是组内四个规格中的第几个规格
        int mod = size - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;
        // group 为组内第一个规格的 index
        return group + mod;
    }
    // 向上取整（Normalizes request size up to the nearest pageSize class.）
    // 如果这里的 pages = 9 (72K),那么就会在 pageIdx2sizeTab 中向上去找最接近的 pageSize
    // 最终会返回 pageIdx = 8
    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }
    // 向下取整（Normalizes request size down to the nearest pageSize class）
    // 比如 pageIdx2sizeTab 中邻近的两个 page size
    // pageIdx = 7 ,对应的 pageSize = 64K (8个page)
    // pageIdx = 8 ,对应的 pageSize = 80K (10个page)
    // 如果这里的 pages = 9 （72K） ,那么就会在 pageIdx2sizeTab 中向下去找最接近的 pageSize
    // 最终会返回 pageIdx = 7
    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }
    // 如果指定 pages 所占用的 pageSize 夹在 pageIdx2sizeTab 中的两个 pageSize 规格之间的话
    // floor = true , 取上一个 pageSize 的规格（向下）
    // floor = false , 取下一个 pageSize 的规格（向上）
    // 假设 pageSize = 72K ， 夹在 (pageIndex = 7,size = 64K) 与 (pageIndex = 8,size = 80K) 之间
    // floor = true, 则取 pageIndex = 7,size = 64K
    // floor = false ,则取 pageIndex = 8,size = 80K
    /*
     * [8k , 32K] 为 group = 0 组,delta = 8K : 8k,16K,24k,32k
     * [40K, 64k] 为 group = 4 组,delta = 8K : 40K,48k,56k,64k
     * [72K, 96k] 为 group = 8 组(80,96,112,128),delta = 16K : 72K(pageIndex = 7,size = 64K),80k,88k(pageIndex = 8,size = 80K),96k
     * [104K, 128k] 为 group = 8 组,104K(pageIndex = 9,size = 96K),112k,120k(pageIndex = 10,size = 112K)，128k
     * [136K, 160k] 为 group = 12 组,delta = 32K,136K(pageIndex = 11,size = 128K),144k(pageIndex = 11,size = 128K),152k(pageIndex = 11,size = 128K),160k
     * [168K, 192k] 为 group = 12 组,168k(pageIndex = 12,size = 160K),176k(pageIndex = 12,size = 160K),184k(pageIndex = 12,size = 160K),192k
     * [200K, 224k] 为 group = 12 组,200(pageIndex = 13,size = 192K),208(pageIndex = 13,size = 192K),216k(pageIndex = 13,size = 192K),224k
     * [232K, 256k] 为 group = 12 组,232k(pageIndex = 14,size = 224K),240k(pageIndex = 14,size = 224K),248k(pageIndex = 14,size = 224K),256k
     * */
    private int pages2pageIdxCompute(int pages, boolean floor) {
        // pages = 512 . pageSize = chunkSize = 4M
        int pageSize = pages << pageShifts;
        if (pageSize > chunkSize) {
            // nPSizes = 32(表示 pageTable 中不存在这样的尺寸) , chunkSize 对应的 pageIndex = 31
            return nPSizes;
        }
        // pageSize 向上取最接近 2 的次幂的 pageSize
        // 比如，24k,3 个 page,但不是 2 的次幂，所以向上取 32k（x = 15）,4 个page
        // 40k,5 个 page ,向上取 64K（x = 16)，8 个 page
        // 4M (x = 22), 正好是 512 个 page ,正好是 2 的次幂
        int x = log2((pageSize << 1) - 1);

        // 按照查找粒度 （page 的倍数：1倍，2倍，3倍，4倍 ... 512倍），以 32k(LOG2_SIZE_CLASS_GROUP + pageShifts) 为间隔，将 4M 范围内的 pageSize 划分成多个组
        // 比如 [8k , 32K]，[40K, 64k]，[72K, 96k]，[104K, 128k]，[136K, 160k]，[168K, 192k]... 直到划分到 [ .... , 4M]
        // 每组 4 个 page 的查找 size, 比如第一组的查找 size 为 8K,16K,24K,32K,组内查找 size 按照 8k 递增。
        // 以上是按照查找 size 进行划分的组，下面 pageIdx2sizeTab 中所规定的所有 pageSize 也会分组
        // pageIdx2sizeTab 每 4 个（LOG2_SIZE_CLASS_GROUP）pageSize 划分为一组。
        // 比如 pageIndex [0,3] 一组，[4,7] 一组，[8,11] 一组，[12,15] 一组 ..... [28,29.30,31] 为一组,共 8 组
        // 每一组的编号为该组起始的 pageIndex
        // 比如 24K 它的 x = 15, 就属于 [0,3] 一组，group = 0 , shift = 0
        // 比如 40K 它的 x = 16, 就属于 [4,7] 一组，group = 4 , shift = 1
        // 比如 72K 它的 x = 17, 就属于 [8,11] 一组，group = 8 , shift = 2
        // 比如 136K 它的 x = 18,就属于 [12,15] 一组，group = 12 , shift = 3
        // 比如 4M 它的 x = 22,就属于 [28,31] 一组，group = 28 , shift = 7
        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);
        // pageIdx2sizeTab 中的分组编号，组内起始 pageIndex
        int group = shift << LOG2_SIZE_CLASS_GROUP;
        // pageIdx2sizeTab 分组内，pageSize 之间的间距。
        // pageSize 在 64K 以下，间距为一个 page ,64K 以上，间距为 x - LOG2_SIZE_CLASS_GROUP - 1 = x - 3
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;
        // 计算 pageSize 属于分组内的第几个 pageIndex
        int mod = pageSize - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;
        // 计算 pageSize 对应在 pageIdx2sizeTab 中的 pageIdx
        int pageIdx = group + mod;
        // 如果 pageSize 恰好夹在两个 pageSize 规格之间 ( pageIdx2sizeTab[pageIdx-1] , pageIdx2sizeTab[pageIdx] )
        if (floor && pageIdx2sizeTab[pageIdx] > pages << pageShifts) {
            // 取 pageIdx2sizeTab 中的下一个规格
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private static int alignSizeIfNeeded(int size, int directMemoryCacheAlignment) {
        if (directMemoryCacheAlignment <= 0) {
            return size;
        }
        int delta = size & directMemoryCacheAlignment - 1;
        // delta == 0 表示 size 刚好对齐 directMemoryCacheAlignment（整数倍）
        // 否则 size - delta 先变为整数倍然后在加上 directMemoryCacheAlignment
        // size 始终要向上对齐至 directMemoryCacheAlignment 的最小整数倍
        return delta == 0? size : size + directMemoryCacheAlignment - delta;
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);
        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
