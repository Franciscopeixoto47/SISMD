# Report

SISMD 2026 - Histogram Equalization and Parallel Processing in Java

Francisco Peixoto - 1211648

---
## Introduction
This report analyzes a Java image-processing application that performs histogram equalization on input images using five
different approaches: 
- Sequential
- Multithreaded (Without Thread Pools) 
- Multithreaded Solution (With Thread Pools)
- Fork/Join Framework
- CompletableFutures-Based

The program measures the execution time, process CPU time, and average CPU utilization for each run. 
This parameter analysis makes it possible to evaluate concurrency behavior, different implementations, performance 
differences, and the impact of garbage collection under different execution settings.

---
## Objectives
The first goal is to compare the performance of each implementation when faced with different-sized images, and thus, different loads.
Each implementation processes the image and writes an output image, so observed differences are caused by execution strategy.

The second goal is to assess the performance of each implementation, across different numbers of threads for the same image.

The third goal is to determine whether G1GC behavior and the GC-oriented execution setup improved runtime stability or 
reduced overhead when compared with the non-GC dataset.

---
## Implementation Approaches
This section describes the implementation strategies developed for the histogram equalization filter. The sequential 
version was used as the baseline approach for the histogram equalization filter.

Across all parallel approaches, the same general processing pipeline was preserved: the image was divided into independent 
row ranges, each worker computed a private local histogram, the partial histograms were merged into a final global 
histogram, and the remaining stages of cumulative distribution computation, equalization, and image output were executed 
sequentially. This design minimizes shared-state contention during histogram construction and keeps the implementations 
structurally comparable.

### 1. Sequential
The sequential solution was implemented as the baseline approach for the histogram equalization filter.

In this version, the image is processed entirely in a single thread, meaning that each pixel is read, its luminosity is
computed, the histogram is updated, the cumulative distribution function is calculated, and finally, the equalized image
is generated in an ordered sequence.
In the code, this behavior is represented by the HistogramFilter method, which performs all steps one after another 
without using any concurrency mechanism.

Because it executes all operations on only one processing core, it does not take advantage of multicore processors and
may become inefficient for large images.


### 2. Multithreaded Solution (Without Thread Pools)
The `multithreadedHistogramFilter` method implements a manual multithreaded solution in which threads are explicitly 
created and managed.
The implementation uses a producer-consumer strategy for work distribution. A producer thread divides the image into 
ranges of rows and places each block into a shared task queue.
Multiple consumer threads remove tasks from this queue and process the corresponding image rows concurrently. 
This allows the work to be distributed dynamically among the consumers instead of assigning a fixed portion of the image 
to each thread in advance.

Synchronization is required because the task queue and histogram are shared among multiple threads.
Access to the task queue is protected using `synchronized (queueLock)`, ensuring that only one thread can add or remove tasks at a time. 
When the queue is empty, consumer threads call `wait()` and temporarily suspend execution until the producer inserts new 
work and calls `notifyAll()`.

The shared histogram also requires synchronization. Since the operation `hist[lum]++` is not atomic, multiple threads 
updating the same histogram bin simultaneously could cause race conditions and incorrect results.
To prevent this, the implementation protects histogram updates with `synchronized (histLock)`, keeping mutual exclusion during each increment.

After all tasks are produced, the producer sets the `producerDone` flag and notifies the waiting consumers so they can 
terminate correctly when the queue becomes empty. The main thread then calls `join()` on both the producer and all consumer 
threads to ensure that histogram equalization is only performed after all parallel work has completed.

One limitation of this implementation is that synchronization occurs for every histogram update, which may reduce performance 
due to lock contention.


### 3. Multithreaded Solution (With Thread Pools)
The `threadPoolHistogramFilter` method implements a multithreaded solution using a thread pool.
Instead of manually creating and managing individual threads, the method creates an `ExecutorService` with 
`Executors.newFixedThreadPool(numThreads)`, which manages a reusable set of worker threads.
This reduces the overhead caused by repeatedly creating and destroying threads.

The image is divided into row ranges, and one task is submitted to the thread pool for each worker. Each task processes 
a specific section of the image and computes the changes in that section.
The thread lifecycle management is delegated to the executor, which simplifies the implementation and improves resource utilization.

This implementation also includes explicit synchronization for the shared histogram. All worker tasks update the same 
`hist` array, so access to the critical section is protected with `synchronized (histLock)`. This ensures that only one 
worker thread at a time can increase a histogram bin, preventing race conditions when multiple tasks process pixels concurrently.

The end of the task is coordinated using `CountDownLatch`. Each worker task calls `countDown()` when it finishes, and the 
main thread calls `await()` to block until all tasks are complete. `CountDownLatch` is a thread-safe synchronization 
mechanism commonly used to wait for a set of concurrent operations to finish before continuing to the next stage of computation.

Once all tasks have completed, the method computes the cumulative histogram, applies histogram equalization, and writes 
the output image.


### 4. Fork/Join Framework
This implementation uses Java’s Fork/Join framework to parallelize histogram equalization. The framework is designed for 
divide-and-conquer problems, where a large task is split into smaller subtasks, processed in parallel, and then combined.
In this solution, the image is divided by rows. Large row ranges are split into smaller ones until they are small enough 
to be processed directly. 

The image processing has three main steps:
1. Compute the **histogram** of pixel luminosity values.
2. Compute the cumulative histogram.
3. Apply the **equalization** formula to create the output image.

The 1st and 3rd steps are the most demanding as they process all pixels in the image, therefore, those two steps are parallelized with Fork/Join.

##### 1st - Histogram Computation
The histogram construction is implemented through the `HistogramTask` class, which extends `RecursiveTask<int[]>`.
This task receives a row interval of the image and either processes it directly or splits it into two smaller subtasks
if the interval is larger than the threshold.

When the task reaches the base case, it computes a local histogram for its assigned rows. Each task stores results in
its own local `int[256]` array, which avoids synchronization on every pixel update and reduces contention between threads.

When a task is larger than the threshold, it is divided into two subtasks at the midpoint of the row range. One subtask
is forked, the other is computed immediately, and then both partial histograms are joined by summing their corresponding
bins. This is the divide-and-conquer pattern of the Fork/Join framework.


##### 2nd - Equalization Computation
After the global histogram is produced, the cumulative distribution function is calculated. This cumulative histogram is
then used to remap each pixel intensity according to the histogram equalization formula.

The equalization phase is implemented using `EqualizationTask`, which extends `RecursiveAction`, as the task performs 
work on the destination image but does not need to return a computed value.

Like the histogram phase, the equalization phase recursively splits the image into smaller row intervals. Each subtask
writes only to its own rows in the output image, which preserves independence between subtasks and allows safe parallel execution.


### 5. CompletableFutures-Based
The `completableFutureHistogramFilter` implementation applies histogram equalization using Java’s `CompletableFuture` API to 
express the algorithm as a pipeline of asynchronous, parallel stages over the image rows. It splits the work into two 
main phases: parallel histogram computation and parallel equalization, both coordinated by a work-stealing thread pool 
created with `Executors.newWorkStealingPool(numTasks)`, which allows worker threads to balance tasks dynamically across available processors.

In the first phase, the method partitions the image by rows into `numTasks` horizontal bands and launches one
`CompletableFuture<int[]>` per band using `supplyAsync` on the custom executor. Each of these tasks iterates over its assigned 
rows and columns, calculates the grayscale luminosity for each pixel, and accumulates a local histogram of 256 intensity 
levels. Once all per-band futures are created, `CompletableFuture.allOf(histFutures)` is used as a barrier to wait for their 
completion, and in the continuation (`thenApply`) all local histograms are merged into a single global histogram array by 
summing the counts per intensity.

Next, the merged histogram is transformed into a cumulative distribution function using `computeCumulative`, which returns 
both the cumulative array and the minimum non-zero CDF value needed for standard histogram equalization scaling. This is
modeled as another future, `cumulativeFuture`, obtained by applying `thenApply` to the merged histogram future so that the 
cumulative statistics are only computed once all histogram tasks have finished. At this point, the algorithm has all the 
global information required to remap pixel intensities while keeping the overall structure fully asynchronous.

The final phase equalizes the image in parallel by composing on `cumulativeFuture` with `thenCompose`, which creates a new 
grayscale `BufferedImage` and defines another set of `numTasks` row-partitioned tasks. These tasks are started with `runAsync`, 
since they do not return values but update disjoint row ranges of the shared output image, avoiding write conflicts. 
For each pixel, the task reads the original luminosity, looks up its CDF value, applies the equalization formula using the 
total number of pixels and `cdfMin`, clamps the result to the range, and writes the corresponding grayscale RGB value into 
the output image. All equalization tasks are synchronized using `CompletableFuture.allOf(eqFutures)`, which completes only 
when every band has been processed, and its continuation returns the fully equalized image.

Finally, `imageFuture.thenAccept(...)` writes the equalized image to disk once all previous stages have completed, and
`.join()` on the resulting future blocks the calling thread until the whole pipeline finishes or an error occurs. 
Any `IOException` that happens during writing is wrapped in a `CompletionException` and then unwrapped in the surrounding
`try/catch` block, so that the method still exposes a checked `IOException` to its callers while internally relying on
`CompletionException` for error propagation in the asynchronous chain. The executor is shut down in a `finally` block to 
ensure resources are cleaned up after the asynchronous processing completes, regardless of success or failure.


### 6. Garbage Collector
To assess the effect of memory management on the performance of the image-processing application, the Java Virtual Machine (JVM)
was explicitly configured to use the G1 garbage collector.
This workload repeatedly allocates large image buffers, histogram arrays, and temporary structures during histogram 
computation and equalization, making garbage collection behavior relevant to both execution time and runtime stability.
The tuning process therefore focused on selecting a collector suitable for relatively large heaps, bursty allocation patterns, and parallel execution.

##### JVM Configuration
The application was executed with the following JVM flags:

**JVM Flags**
- -Xms512m
- -Xmx2048m
- -XX:+UseG1GC
- -XX:MaxGCPauseMillis=100
- -Xlog:gc*:file=gc.log:time,uptime,level,tags

These settings define an initial heap size of 512MB, a maximum heap size of 2048MB, the use of the G1 garbage collector, 
a target maximum pause time of 100ms, and detailed garbage collection logging to gc.log.

##### G1 GC
The G1 garbage collector was selected as it is designed for applications that use relatively large heaps and benefit
from predictable pause times. In the lectures, it is highlighted that G1's focus on meeting pause-time goals by partitioning 
the heap into regions, collecting incrementally with parallel evacuation, and using concurrent marking with mixed collections
to reclaim space without long full-heap pauses. The region-based evacuation also provides compaction, helping limit
fragmentation when many large, short-lived objects are allocated.

Unlike simpler collectors, G1 divides the heap into regions and performs mostly incremental evacuation and
reclamation, which helps control pause duration while still supporting high allocation rates.

This is appropriate for the application as:
- The application creates large temporary objects and image buffers, especially when processing high-resolution images.
- Several implementations use multiple threads, which increases allocation pressure and may amplify garbage collection costs.

The heap bounds were also chosen as the setting: 
- -Xms512m avoids excessively small initial heap sizes that could trigger early resizing.
- -Xmx2048m provides enough space for large image data and temporary arrays without immediately forcing aggressive reclamation.
- Pause-time target of 100ms was chosen to encourage low pauses during execution.

##### GC Logs
The garbage collector configuration was validated using the generated GC logs located at StarterCode/src/gc.log.x 
These logs confirm that the expected configuration was applied and provide evidence of runtime behavior.

The logs show the following:
- G1 was active, as indicated by entries such as Using G1.
- The JVM version was 17.0.17+10.
- Heap sizing matched the configuration, with entries reporting Heap Min Capacity: 512M and Heap Max Capacity: 2G.
- The heap region size was 1M, consistent with G1's region-based design.
- Young-generation collection pauses were consistently short, typically in the range of approximately 4ms.
- In some runs, pauses increased to approximately 8ms (for example in gc.log.4), but still remained well below the configured 100ms target.
- Evacuation phases used multiple GC workers, with log entries indicating 12 workers active out of 16 configured parallel 
workers, showing that the collection itself was parallelized.
- No Full GC events were recorded, since no Pause Full entries appeared in the logs.
- The number of humongous regions varied substantially between runs, from small values such as 2 to 6 up to 144, indicating
that larger images generated substantially more large-object allocations.
- Final heap summaries showed moderate retained heap occupancy after execution, typically between approximately 70MB and
350MB, while metaspace usage remained stable at around 2.9 to 3.0MB.

Overall, the logs indicate that the collector operated as intended: it maintained short pauses, scaled collection work 
across threads, and avoided costly Full GC cycles.

##### Impact on Performance and Resource Usage
To evaluate the impact of the garbage collector, results obtained with the GC configuration were compared against a 
baseline dataset without the same GC-focused configuration (metrics_GC.csv vs metrics_noGC.csv). 
The analysis considered execution time and CPU utilization.
Across all implementations, the average execution times were as follows:
- Implementation 1: 1879.955ms with GC versus 1796.004ms without GC
- Implementation 2: 1873.752ms with GC versus 1565.191ms without GC
- Implementation 3: 1863.774ms with GC versus 1544.135ms without GC
- Implementation 4: 1462.010ms with GC versus 1290.037ms without GC
- Implementation 5: 1374.234ms with GC versus 1369.321ms without GC

These results show that the GC configuration generally increased total execution time, particularly for implementations
2 and 3, while implementation 5 remained almost unchanged on average. CPU utilization remained broadly similar across 
both datasets, suggesting that the main effect of the configuration was not a major change in processor demand, but rather 
additional memory-management overhead.

##### Conclusion
The tuning results indicate that G1GC was successful in achieving its intended operational goals. The collector remained 
active during execution, maintained short young-generation pauses, used parallel evacuation workers, and avoided Full GC 
events even under large-image workloads.

However, these benefits did not translate into a consistent throughput improvement, as in most cases, total runtime increased 
slightly when the GC configuration was enabled. 
This is particularly visible in the more allocation-intensive parallel implementations, where garbage collection overhead 
appears to have offset any benefit gained from pause-time control. 
Therefore, the measured results suggest that this program is dominated more by overall throughput than by pause latency.

In summary, the chosen garbage collector aligned well with the application's allocation profile and resource usage characteristics, 
and the logs provide clear evidence that the configuration operated correctly. The main improvement observed was runtime 
predictability and the absence of Full GC events, rather than faster execution.


---
## Concurrency and Synchronization
Histogram equalization is divided into two main phases.
The first computes the histogram of image luminosity values, which can be parallelized by splitting the image into independent regions.
The second phase applies the cumulative distribution function to each pixel to generate the equalized grayscale output image, 
which is also parallelizable because each pixel can be processed independently once the histogram has been fully computed.


### 1. Sequential implementation
The sequential implementation is provided through the `sequentialHistogramFilter` method, which just calls `HistogramFilter` 
(this is done only to more clearly organize the code).
This version contains no concurrency and therefore requires no synchronization.

The method processes the image in three strictly ordered stages:

1. It traverses all pixels to compute the histogram.
2. It computes the cumulative histogram and the minimum non-zero cumulative value.
3. It traverses the image again to generate the equalized grayscale output.

Because all operations are executed by a single thread, shared-memory conflicts do not occur.
Every read and write is naturally ordered by program execution, and no race conditions are possible.
This version serves as the baseline for correctness and performance comparison.


### 2. Multithreaded implementation (without thread pools)
The `multithreadedHistogramFilter` method implements a producer-consumer model using explicit `Thread` objects, a shared 
task queue, and monitor-based synchronization with `synchronized`, `wait`, and `notifyAll`.

##### Concurrency model
This implementation creates:
- One producer thread
- Multiple consumer threads
- One shared task queue containing row intervals
- One shared global histogram used only during the merge phase

The image is divided into blocks of rows.
The producer thread generates tasks in the form of `[startRow, endRow]` intervals and inserts them into a shared `LinkedList`.
Consumer threads repeatedly remove tasks from the queue and process the corresponding rows.

This design introduces dynamic work distribution.
Instead of assigning a fixed region to each thread at startup, consumers retrieve blocks as they become available.
This can improve load balancing, especially if some tasks take slightly longer than others.

##### Synchronization strategy
Synchronization is centered around the `queueLock` object.
It protects access to two shared resources:
- The `taskQueue`
- The `producerDone` completion flag

Consumers enter a waiting state when the queue is empty but production is not yet complete:
```java
while (taskQueue.isEmpty() && !producerDone[0]) {
    queueLock.wait();
}
```

The producer calls `notifyAll()` whenever it inserts a new task and again when it marks production as finished.
This ensures that waiting consumers are awakened when work becomes available or when termination conditions change.

This implementation correctly uses a `while` loop around `wait()` for handling wakeups and rechecking the shared condition safely.

##### Histogram synchronization
Each consumer maintains its own private `localHist` array, as it avoids contention during the pixel-processing phase.
If all threads incremented a single shared histogram directly, synchronization overhead would be extremely high due to frequent concurrent updates.

Once a consumer finishes all assigned tasks, it merges its local histogram into the global `hist` array inside:

```java
synchronized (hist) {
    for (int i = 0; i < 256; i++) {
        hist[i] += localHist[i];
    }
}
```

##### Strengths
- Explicit demonstration of classical concurrency concepts
- Clear producer-consumer coordination
- Local accumulation reduces contention
- Dynamic task assignment can improve load balancing

##### Limitations
- High implementation complexity compared to higher-level abstractions
- Manual management of lifecycle, waiting, notification, and interruption
- Risk of programming errors such as missed notifications or deadlocks in more complex variants
- The equalization phase remains sequential in this method, so parallelism is applied only to histogram construction


---
## 3. Multithreaded implementation (with thread pools)
The `threadPoolHistogramFilter` method uses the `ExecutorService` API with a fixed-size thread pool.
This approach replaces explicit thread creation with a managed execution framework.

##### Concurrency model
The image is statically partitioned by rows.
Each thread is assigned a fixed interval:
- Thread `t` processes rows from `t * height / numThreads`
- Up to `(t + 1) * height / numThreads`

Each task computes a private local histogram stored in `localHists[threadId]`.
Because each worker writes only to its own array, no synchronization is needed during histogram accumulation.

This model is simpler because work is assigned once, with no shared queue and no runtime task stealing.

##### Synchronization strategy
The main coordination mechanism is `CountDownLatch`.
Each submitted task decrements the latch in a `finally` block after completion:
```java
finally {
    latch.countDown();
}
```

The main thread waits for all histogram tasks to complete using:
```java
latch.await();
```

This ensures that histogram merging only starts after every worker has finished processing its assigned rows.
No explicit locking is required for task scheduling or execution. The executor framework handles worker thread reuse and lifecycle management internally.

##### Histogram synchronization
There is no concurrent access to the same histogram array during computation.
Each thread writes exclusively to its own `localHists[threadId]`, and the final merge is performed sequentially by the 
main thread after `latch.await()` returns. This completely removes the need for synchronization during histogram merging.

##### Strengths
- Simpler and safer than manual thread management
- Avoids queue-based locking overhead
- Uses private histograms to eliminate contention
- Reuses threads efficiently through the executor service

##### Limitations
- Static partitioning may lead to load imbalance if tasks are uneven, although row-based image processing is usually regular enough that this is not a serious issue
- The equalization phase is still sequential
- Requires explicit shutdown of the executor to release resources


---
## 4. Fork/Join implementation
The `forkJoinHistogramFilter` method uses the `ForkJoinPool`, which is designed for recursive divide-and-conquer parallelism.
This implementation delegates the work to `HistogramTask` and `EqualizationTask`, executed through a custom `ForkJoinPool`.

##### Concurrency model
In this implementation, the computation is recursively split into smaller subproblems.
Each task operates on a range of rows and may:
- Compute the result directly if the range is small enough
- Split the range into smaller subtasks otherwise

This model is particularly well suited for data-parallel image operations because large images can be recursively 
partitioned until each task reaches a practical granularity.

##### Synchronization strategy
Fork/Join relies primarily on structured task decomposition rather than explicit user-managed synchronization.
The framework coordinates tasks internally using work-stealing queues.
Idle worker threads can steal tasks from busy workers, which improves load balancing automatically.

The histogram phase returns an `int[]` from `HistogramTask`.
In a typical Fork/Join design, each subtask computes its own local histogram and parent tasks merge child histograms when joining results.
This avoids shared mutable state during parallel execution and therefore minimizes the need for locks.

The equalization phase is also parallelized through `EqualizationTask`.
Since each task writes to a disjoint row range of the output image, synchronization is unnecessary as long as tasks do not overlap in their writes.

##### Histogram synchronization
In this model, synchronization is implicit in the `join()` operation between subtasks.
Rather than locking a shared histogram, tasks return partial results, and those results are combined after task completion.
This is often more scalable than shared-state synchronization because it follows a divide-and-conquer reduction pattern.

##### Strengths
- Parallelizes both histogram computation and equalization
- Work-stealing improves load balancing
- Avoids most explicit synchronization logic
- Well suited for recursive decomposition of large workloads

##### Limitations
- Efficiency depends on the threshold used to stop recursion
- Overhead may be unnecessary for small images


---
## 5. CompletableFuture implementation
The `completableFutureHistogramFilter` method uses `CompletableFuture` with a work-stealing executor.
This implementation expresses concurrency as a pipeline of asynchronous stages rather than explicit threads or recursive tasks.

##### Concurrency model
This method divides processing into two asynchronous phases:
1. Parallel histogram computation
2. Parallel equalization after histogram aggregation

First, multiple asynchronous suppliers compute local histograms for disjoint row ranges.
These futures are stored in `histFutures`.
After all histogram tasks are complete, they are merged into a single histogram through:

```java
CompletableFuture<int[]> mergedHistFuture =
        CompletableFuture.allOf(histFutures)
                .thenApply(v -> {
                    int[] merged = new int[256];
                    for (CompletableFuture<int[]> future : histFutures) {
                        int[] local = future.join();
                        for (int i = 0; i < 256; i++) {
                            merged[i] += local[i];
                        }
                    }
                    return merged;
                });
```
The cumulative histogram is computed as another dependent stage.
Finally, a second set of asynchronous tasks applies equalization in parallel, each operating on a separate row range of the output image.


##### Synchronization strategy
The synchronization mechanism here is completion dependency management.
`CompletableFuture.allOf(...)` acts as a barrier, ensuring that subsequent stages only begin after all previous tasks have completed.

This greatly reduces the need for explicit synchronization. There are no `wait`, `notify`, or explicit locks in the implementation.
Ordering is defined declaratively by future composition.

The only shared structure during histogram computation is the source image, which is read-only.
Each histogram task creates its own private histogram array, so concurrent writes do not interfere.
During equalization, tasks write into the shared output image, but each task handles a disjoint range of rows, which 
avoids overlapping writes and therefore avoids data races in practice.

##### Strengths
- Clear expression of task dependencies
- Parallelizes both histogram construction and equalization
- Minimal explicit synchronization
- Good composability and maintainability
- Uses asynchronous pipelines rather than manual coordination

##### Limitations
- More abstract than manual threads, which may make execution flow less obvious
- Debugging asynchronous pipelines can be harder than debugging sequential code
- `CompletableFuture` introduces framework overhead that may not pay off for small workloads


---
## Performance Analysis
This analyzes is made based on the execution times, CPU times, and average CPU utilization of each implementation across 
different thread counts and image sizes. The analysis is divided into two parts: 
- Runs without GC - based on the data obtained in ``StarterCode\docs\metrics_noGC.csv``.
- Runs with GC - based on the data obtained in ``StarterCode\docs\metrics_GC.csv``, and using the JVM flags indicated priviously.

This is done to assess the impact of garbage collection on performance.

The sequential baseline is assumed as implementation 1 with 1 thread.

### Efficiency Gains
#### Without GC
The sequential baseline averages 1796.0 ms across the five images.
Implementations 2 and 3 improve only slightly, while 4 and 5 deliver larger reductions in execution time versus the baseline.

**Baseline comparison**
Using the sequential baseline as the reference:
- Implementation 2 reaches its best average result at 4 threads with a speed increase of 1.068x - about 4.0% time reduction. 
- Implementation 3 performs slightly better, peaking at 4 threads with a 1.103x speedup and a 7.3% time reduction.
- Implementation 4 is the first version that shows significant efficiency, with its best result at 16 threads: 
1000.7 ms average execution time, corresponding to 1.479x speedup, and 15.5% lower average runtime. 
- Implementation 5 is the best on average, also at 16 threads, with 986.6 ms execution time, corresponding to 1.492x speedup, 
and an 18.2% time reduction.

**Best cases**
The largest gains appear on bigger inputs, not on the smallest image. 
For src10K.jpg, the best run is implementation 5 with 8 threads at 2336.4 ms, corresponding to a 1.922x speedup, and 48.0% lower runtime;
For src7K.jpg, implementation 4 with 16 threads reaches 1418.9 ms, a 2.165x speedup, and 53.8% reduction.
On the smallest 1k, src.jpg, none of the parallel implementations beat the sequential baseline, meaning that the
setup and synchronization costs overcome the work time itself.

**Conclusion**
If the image size is small, the sequential version is best. However, as the image size increases, the more advanced 
parallel implementations become more efficient, namely implementations 4 and 5.

#### With GC
Implementations 4 and 5 deliver the clearest efficiency gains over the sequential baseline, while
implementations 2 and 3 show only modest or inconsistent improvements overall.
The strongest gains appear on larger images and higher thread counts, especially at 16 threads.

Compared to the sequential baseline:
- Implementation 5 has the best average efficiency gain across the tested runs, with an average speedup of 1.37x and an 
average execution-time reduction of 18.6%.
- Implementation 4 is the second-best overall, with an average speedup of 1.30x and an average improvement of 12.0%.
- Implementation 3 reaches 1.07x.
- Implementation 2 only 1.04x on average.

**Best cases**
The best result for implementation 4 is on src7K.jpg with 16 threads, where execution time drops to 1472.835 ms, 
from the 3565.694 ms baseline to 1472.835 ms, giving a 2.42x speedup and a 58.7% reduction in time.
Implementation 5 is very close, also on src7K.jpg with 16 threads, reducing runtime to 1504.431 ms for a 2.37x speedup and a 57.8% improvement.

**Conclusion**
Implementations 2 and 3 shows some benefit only at moderate thread counts, but its average gain is nearly flat and even slightly 
negative in time reduction overall, so its efficiency improvement is limited.
The biggest difference is in implementations 4 and 5, which appear much better at converting extra threads into lower 
execution time, especially for larger workloads.


---
### Scalability
#### Without GC
Implementations 2 and 3 scale a bit from 1 to 4 threads, but gains flatten or become inconsistent beyond that point.
This suggests parallel overhead starts offsetting the benefits. 
By contrast, implementations 4 and 5 keep improving as threads increase, mostly on larger images, and especially from 
2 to 16 threads, showing that these designs use parallelism more effectively.
The data also shows that parallelism helps far more on medium and large inputs than on the smallest image, where overhead 
often cancels out any benefit.

**Core scaling**
Implementation 2 improves from its own 1-thread average by about 1.14x at 2 threads, 1.21x at 4 threads, and 1.25x at 
8 threads, but then slips slightly at 16 threads, which suggests diminishing returns after 8 cores. 
Implementation 3 follows a similar pattern, rising more steadily to 1.24x versus its own 1-thread version at 16 threads, 
but without a strong jump at high core counts.

Implementation 4 scales much more effectively, when compared with its own 1-thread version, it reaches 1.41x at 2 threads, 
1.61x at 8 threads, and 1.77x at 16 threads on average.
Implementation 5 has the strongest internal scaling, going from a weak 1-thread result to 2.04x at 2 threads and 
2.51x at 16 threads, meaning it benefits the most from added cores.

**Data size scaling**
For small inputs, scalability is poor across all parallel implementations. On the 1k src.jpg, the sequential baseline remains 
best, while even the best parallel result from implementation 5 at 2 threads still achieves only 0.938x of the baseline, 
so added cores do not help when the workload is too small.

As image size grows, the more efficient implementations stand out. For src10K.jpg:
- implementation 2 peaks at 8 threads with 1.282x speedup over baseline,
- implementation 3 peaks at 16 threads with 1.310x, 
- implementation 4 reaches 1.771x at 16 threads,
- implementation 5 reaches 1.922x at 8 threads. 

On src7K.jpg:
- implementations 4 and 5 both pass 2x speedup at 16 threads,
- 2 and 3 stay near 1.28x to 1.30x, 

Showing that only 4 and 5 scale strongly with both problem size and core count.

**Conclusion**
Implementation 2 has limited scalability, because extra cores help only a little and flattens quickly. 
Implementation 3 is slightly better, but still small in strong scaling terms. 
Implementations 4 and 5 are the only ones that show convincing scalability, since they keep reducing runtime as cores 
increase and their advantages grow on larger datasets.


#### With GC
Implementations 2 and 3 scale a bit, but gains flatten beyond that point. This suggests parallel overhead starts offsetting the benefits.
By contrast, implementations 4 and 5 keep improving as threads increase, mostly on larger images.
The data also shows that parallelism helps far more on medium and large inputs than on the smallest image, where overhead
often cancels out any benefit.

**Core scaling**
Looking at strong scaling relative to each implementation’s own 1-thread runtime:
- Implementation 2 peaks at 1.14x at 4 threads, gaining slightly from parallelism but does not scale well after.
- Implementation 3 peaks at 1.21x at 4 threads, gaining slightly from parallelism but does not scale well after.
- Implementation 4 reaches its best average core scaling at 16 threads with a 1.78x speedup,
- Implementation 5 reaches 1.76x at 16 threads.

Scaling efficiency drops as thread count rises for every implementation, which means extra cores bring diminishing 
returns rather than near-linear speedup.

**Data size scaling**
When input size grows from 1k src.jpg to src10K.jpg, implementations 4 and 5 handle the increase much better at higher thread 
counts than implementations 2 and 3.
At 16 threads the runtime growth factor is 22.08x longer for implementation 2 and 24.22x longer for implementation 3.
Hiiwever, for implementation 4 it is only 12.43x longer, and 10.43x for implementation 5, which indicates much better scalability.

**Conclusion**
Implementation 2 has weak scalability overall: it improves slightly up to 4 threads, then stalls or regresses, so it does not exploit additional cores well.
Implementation 3 is slightly better, but it follows the same pattern of early gains followed by flattening.
Implementation 4 scales well with larger workloads and continues to benefit from more cores, especially at 16 threads, 
though with reduced efficiency.
Implementation 5 is the strongest all-around scalable version because it combines strong large-input behavior with the 
lowest growth in runtime as image size increases at higher thread counts.


---
### Overhead Analysis
#### Without GC
As thread count rises, runtime often drops, but CPU work rises much faster, which points to thread-management and synchronization costs. 
This overhead is especially harmful for small size input images and in implementations 2 and 3, while implementations 
4 and 5 accept much higher overhead in exchange for stronger speedups on large inputs.

**Thread overhead**
Comparing total CPU time with elapsed execution time, the sequential baseline has an average CPU-to-execution ratio of 1.253, 
while implementation 2 climbs to 3.524 at 16 threads and implementation 3 to 2.973. 
This means that more processor time is being spent per unit of useful elapsed work as thread count increases. 
This pattern happens due to extra costs from creating, scheduling, coordinating, and synchronizing threads.

**Synchronization cost**
- Implementation 2 shows rising overhead with only small speed gains: at 4 threads it averages 1.068x speedup over baseline, 
but already uses about 25.5% more CPU time than the baseline; at 16 threads it still delivers only 1.042x speedup while
consuming about 189.4% more CPU time. 
- Implementation 3 is slightly better, but only shows: 1.103x speedup at 4 threads for 27.7% extra CPU, and 1.074x at 16 threads for 136.0% extra CPU. 
- Implementation 4 reaches a CPU-overhead ratio of 8.341 at 16 threads, with extra CPU time of 423.7% over baseline, and a speedups of 1.479x
- Implementation 5 reaches 9.233 at 16 threads, with extra CPU time of 472.9% over baseline, and a speedup of 1.492x

**Small-input**
On the smallest image, parallel overhead dominates the workload. For 1k src.jpg, implementation 2 slows from 136.0 ms in 
its 1 thread baseline, to 215.6 ms at 8 threads, while its CPU-overhead ratio jumps from 1.839 in the baseline to 3.624;
at 16 threads it is still slower than baseline and reaches a ratio of 6.032. 

Implementation 3 behaves similarly, with slower runtimes at higher thread counts despite much higher CPU use, showing
that thread startup and synchronization cost more than the work itself.

**Conclusion**
Implementation 2 has the weakest overhead profile, because additional threads add a lot of CPU cost without much runtime benefit. 
Implementation 3 is a bit more efficient but still shows clear diminishing returns from synchronization overhead at higher thread counts.
Implementations 4 and 5 handle overhead better in practical terms. They are more expensive in CPU usage, but their larger 
reductions in time suggest the synchronization cost is justified when the input is large enough.


#### With GC
As thread count rises, runtime sometimes improves, but CPU work grows much faster, which again points to thread-management 
and synchronization overhead. With GC enabled, this effect is still especially harmful for small inputs and for implementations 
2 and 3, while implementations 4 and 5 tolerate much higher overhead because they still extract stronger speedups on large images.

**Thread overhead**
Comparing total CPU time with elapsed execution time, the sequential baseline has an average CPU-to-execution ratio of 
1.126, while implementation 2 rises to 3.256 at 16 threads and implementation 3 to 3.188.

The overhead is much larger in implementations 4 and 5, which reach 8.434 and 8.993 respectively at 16 threads.
This means that, with GC enabled, increasing thread count still causes far more processor time to be spent per unit of
elapsed work, which is consistent with extra scheduling, coordination, synchronization, and waiting costs.

**Synchronization cost**
- Implementation 2 shows rising overhead with weak gains: at 4 threads it averages 1.095x speedup over the sequential 
baseline while already consuming 34.9% more CPU time than the baseline; at 16 threads it drops back to only 1.035x 
speedup while consuming 196.0% more CPU time.
- Implementation 3 is slightly better balanced: at 4 threads it reaches 1.151x speedup for 23.9% extra CPU time, but at 
16 threads it only achieves 1.070x speedup while using 181.0% more CPU time than baseline.
- Implementation 4 accepts much heavier synchronization cost: it reaches 1.403x speedup at 4 threads with 145.7% extra 
CPU time, and 1.596x at 16 threads with 413.7% extra CPU time.
- Implementation 5 shows the same trade-off, but with the strongest results overall: 1.429x speedup at 4 threads for 
118.9% extra CPU time, rising to 1.626x at 16 threads while consuming 445.3% more CPU time than baseline.

That suggests synchronization and worker-coordination overhead are still limiting the theoretical benefit of extra cores, 
but implementations 4 and 5 convert more of that extra activity into lower wall-clock time.

**Small-input**
On the smallest image, parallel overhead dominates the workload. For 1k src.jpg, implementation 2 slows from 156.726 ms at 
1 thread to 185.317 ms at 8 threads and 217.493 ms at 16 threads, while its CPU-to-execution ratio rises from 1.695 to 4.216 and then 4.813.

Implementation 3 behaves similarly, rising from 151.425 ms at 1 thread to 180.859 ms at 8 threads and 199.995 ms at 16 
threads, while its CPU-to-execution ratio climbs from 1.857 to 2.333 and then 5.938.

Implementation 4 reaches 252.883 ms at 8 threads and still remains slower than its 1-thread version even at 16 threads.

Implementation 5 stays close to its 1-thread runtime up to 4 threads but degrades at 8 and 16 threads.

**Conclusion**
Implementation 2 has the weakest overhead profile with GC, because additional threads add a large CPU cost without producing much runtime benefit.
Implementation 3 is somewhat better, but it still shows clear diminishing returns as synchronization overhead grows at higher thread counts.
Implementations 4 and 5 handle overhead better in practical terms: they are much more expensive in CPU usage, but their 
stronger reductions in execution time show that the overhead is justified when the workload is large enough.


---
### Bottlenecks
#### Without GC
The main bottlenecks are parallel overhead, poor scaling on small inputs, and diminishing returns at higher thread counts.
Implementations 2 and 3 hit these limits earliest, while 4 and 5 scale better but still show saturation and signs of 
coordination cost at higher core counts.

**Small-workload limit**
The smallest input is a clear bottleneck case for every parallel design.
For 1k src.jpg, the sequential baseline remains fastest at 136.0 ms, while the best result among parallel versions is 
still slower, such as implementation 5 with 2 threads at 145.0 ms and implementation 2 with 1 thread at 140.6 ms.
This indicates the fixed costs of splitting work, launching threads, and synchronizing results are too large relative 
to the amount of useful computation.

**Diminishing returns**
Implementation 2 improves up to 8 threads, but its average execution time actually gets worse from 1452.7 ms at 8 threads 
to 1474.9 ms at 16 threads. At the same time, its CPU-per-wall ratio rises further, which suggests extra cores are being 
spent on coordination rather than productive work. That makes the 8-to-16 thread step a clear bottleneck region for implementation 2.

**Mid-scaling regression**
Implementation 4 has a different limitation: it improves strongly from 1 to 2 threads, but then regresses from 1250.6 ms 
at 2 threads to 1328.7 ms at 4 threads before recovering again at 8 and 16 threads. That dip suggests a temporary scaling
bottleneck, possibly from load imbalance, contention, or a synchronization pattern that becomes inefficient at that thread 
count. Because performance later improves again, the issue is not a global design failure but a configuration-sensitive bottleneck.

**Implementation limits**
Implementation 2 is limited most by weak parallel scalability, since its best results stay around 1.28x on large inputs 
and stop improving after 8 threads. 
Implementation 3 is slightly better but still bottlenecked by shallow gains, as moving from 4 to 8 threads improves runtime 
by only 1.6% on average and from 8 to 16 by only 1.7%. 
Implementation 4 is strong on large images but shows instability at intermediate thread counts.
Implementation 5 is the best overall, however, it still shows saturation, since going from 8 to 16 threads improves 
average runtime by only 2.8% despite much higher CPU consumption.


#### With GC
The main bottlenecks with GC enabled are again parallel overhead, poor scaling on small inputs, and diminishing returns 
at higher thread counts.
Implementations 2 and 3 reach these limits earliest, while implementations 4 and 5 scale better 
on large workloads but still show saturation and configuration-sensitive regressions as thread count increases.

**Small-workload limit**
The smallest input remains a clear bottleneck case for every parallel design. For 1k src.jpg, the sequential baseline is 
still the fastest at 147.063 ms, while every parallel implementation is slower at most thread counts; the closest results 
are implementation 3 at 1 thread with 151.425 ms and implementation 5 at 4 threads with 153.896 ms.

This shows that, even with GC enabled, the fixed costs of splitting work, launching or coordinating threads, and 
synchronizing results remain too large compared with the amount of useful computation in the smallest workload.

**Diminishing returns**
Implementation 2 improves from 2275.1 ms average runtime at 1 thread to 1701.6 ms at 4 threads, but then performance stops 
improving and slightly regresses to 1728.3 ms at 8 threads and 1733.8 ms at 16 threads.
At the same time, its average CPU-per-wall ratio rises from 1.586 at 4 threads to 2.459 at 8 threads and 3.256 at 16 threads, 
which suggests the extra cores are being spent more on coordination than on useful work.
That makes the 4-to-8 and 8-to-16 thread ranges the clearest bottleneck region for implementation 2 with GC.

**Mid-scaling regression**
Implementation 4 has a different limitation: it scales strongly from 1 to 4 threads, improving from 2342.2 ms average runtime 
at 1 thread to 1190.4 ms at 4 threads, but then regresses to 1223.2 ms at 8 threads before recovering again at 16 threads with 966.8 ms.
That dip appears in specific workloads as well, such as src5K.jpg, where runtime increases from 599.306 ms at 4 threads to 
725.567 ms at 8 threads, and src10K.jpg, where it rises from 3085.234 ms to 3165.939 ms before dropping sharply at 16 threads.

**Implementation limits**
- Implementation 2 is still limited most by weak parallel scalability, since its average runtime improves only up to 4 threads 
and then stalls, while some individual workloads such as src2K.jpg and src10K.jpg actually get worse with more threads.
- Implementation 3 is slightly better, but it is still bottlenecked by shallow gains: its average runtime drops from 1685.6 ms 
at 4 threads to 1720.1 ms at 8 threads, and only recovers marginally to 1717.7 ms at 16 threads.
- Implementation 4 is strong on large images but shows instability at intermediate thread counts, so its bottleneck is not
lack of scalability overall but sensitivity to certain thread configurations.
- Implementation 5 is the best overall with GC, yet it still shows saturation on some workloads, since high thread counts 
do not always help: src2K.jpg gets steadily worse from 210.413 ms at 4 threads to 218.889 ms at 8 threads and 226.965 ms
- at 16 threads, while src.jpg degrades sharply after 4 threads.


---
## Conclusion
This report shows that the effectiveness of parallel histogram equalization in Java depends mainly on the execution strategy, 
the image size, and the cost of coordination between workers.
Across both datasets, implementations 4 and 5 consistently achieved the best overall results, while implementations 2 and 3 
delivered only limited gains as their synchronization and management overhead reduced the practical benefit of additional threads.

The sequential version remains the most appropriate solution for small images, where the workload is too small to compensate 
for thread creation, task scheduling, synchronization barriers, and result merging.
As image size increases, the Fork/Join and CompletableFuture approaches become more effective, showing that well-structured 
parallel decomposition with private local work and reduced shared-state contention is essential for getting useful speedup.

From the scalability analysis, the results also indicate that adding threads does not automatically produce proportional gains. 
Implementations 2 and 3 scale poorly, as lock contention, queue coordination, and sequential merge phases quickly 
become bottlenecks. On the other hand, implementations 4 and 5 use more suitable concurrency models that better exploit multicore 
processors, especially on larger workloads.
Even so, none of the approaches achieves linear scaling, which confirms that synchronization, task overhead, and remaining 
sequential phases still limit the maximum obtainable speedup.

The garbage-collection analysis shows that enabling G1GC does not fundamentally change the ranking of the implementations, 
but it does introduce measurable overhead.
The logs indicate frequent young collections with short pauses and recurring humongous-region usage, which is consistent
with allocation-heavy workloads. This adds CPU cost but does not produce severe latency spikes or unstable execution behavior. 
In practice, the best-performing parallel implementations still retain their advantage on larger images because the 
computational workload is large enough to dominate the additional GC cost.

Overall, it can be concluded that parallelism is beneficial for histogram equalization only the workload is large enough 
to counter weigh coordination overhead.
Among the tested approaches, the Fork/Join and CompletableFuture solutions provide the best balance between concurrency 
structure, scalability, and runtime reduction. This makes them the most suitable designs when dealing with large images,
while the sequential version remains preferable for small inputs.
