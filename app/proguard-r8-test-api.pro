# Release instrumentation Kotlin ABI boundary.
# Inputs: 14 selected R8 fixture classes and all 419 AndroidX Test framework classes.
# Preserve exact externally referenced signatures and descriptor classes; no allow* modifiers.
# Library implementation members absent from this inventory remain eligible for R8.
# Generated from original classfiles, not from already optimized method-owner gaps.

-keep,includedescriptorclasses interface kotlin.Lazy {
    java.lang.Object getValue();
}

-keep,includedescriptorclasses class kotlin.LazyKt

-keep,includedescriptorclasses class kotlin.LazyKt__LazyJVMKt {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}

-keep,includedescriptorclasses class kotlin.Result {
    kotlin.Result$Companion Companion;
    java.lang.Object constructor-impl(java.lang.Object);
    java.lang.Throwable exceptionOrNull-impl(java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.Result$Companion

-keep,includedescriptorclasses class kotlin.ResultKt {
    java.lang.Object createFailure(java.lang.Throwable);
    void throwOnFailure(java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.Unit {
    kotlin.Unit INSTANCE;
}

-keep,includedescriptorclasses class kotlin.collections.ArraysKt

-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt {
    byte[] copyOfRange(byte[], int, int);
}

-keep,includedescriptorclasses class kotlin.collections.CollectionsKt

-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__CollectionsJVMKt {
    java.util.List listOf(java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.collections.CollectionsKt___CollectionsKt {
    java.lang.String joinToString$default(java.lang.Iterable, java.lang.CharSequence, java.lang.CharSequence, java.lang.CharSequence, int, java.lang.CharSequence, kotlin.jvm.functions.Function1, int, java.lang.Object);
    java.lang.Object single(java.util.List);
}

-keep,includedescriptorclasses interface kotlin.coroutines.Continuation {
    kotlin.coroutines.CoroutineContext getContext();
    void resumeWith(java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.coroutines.ContinuationKt {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses interface kotlin.coroutines.CoroutineContext

-keep,includedescriptorclasses class kotlin.coroutines.EmptyCoroutineContext {
    kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}

-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt

-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt {
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
    java.lang.Object getCOROUTINE_SUSPENDED();
}

-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    kotlin.coroutines.Continuation create(java.lang.Object, kotlin.coroutines.Continuation);
    java.lang.Object invokeSuspend(java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.Boxing {
    java.lang.Boolean boxBoolean(boolean);
    java.lang.Integer boxInt(int);
}

-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.ContinuationImpl {
    <init>(kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.DebugProbesKt {
    void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses interface kotlin.coroutines.jvm.internal.SuspendFunction

-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.SuspendLambda {
    <init>(int, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlin.io.CloseableKt {
    void closeFinally(java.io.Closeable, java.lang.Throwable);
}

-keep,includedescriptorclasses class kotlin.io.FilesKt

-keep,includedescriptorclasses class kotlin.io.FilesKt__FileReadWriteKt {
    byte[] readBytes(java.io.File);
    java.lang.String readText$default(java.io.File, java.nio.charset.Charset, int, java.lang.Object);
    void writeBytes(java.io.File, byte[]);
}

-keep,includedescriptorclasses class kotlin.io.FilesKt__UtilsKt {
    boolean deleteRecursively(java.io.File);
}

-keep,includedescriptorclasses interface kotlin.jvm.functions.Function0 {
    java.lang.Object invoke();
}

-keep,includedescriptorclasses interface kotlin.jvm.functions.Function1 {
    java.lang.Object invoke(java.lang.Object);
}

-keep,includedescriptorclasses interface kotlin.jvm.functions.Function2 {
    java.lang.Object invoke(java.lang.Object, java.lang.Object);
}

-keep,includedescriptorclasses interface kotlin.jvm.functions.Function3

-keep,includedescriptorclasses class kotlin.jvm.internal.DefaultConstructorMarker

-keep,includedescriptorclasses class kotlin.jvm.internal.FunctionReferenceImpl {
    <init>(int, java.lang.Object, java.lang.Class, java.lang.String, java.lang.String, int);
}

-keep,includedescriptorclasses class kotlin.jvm.internal.Intrinsics {
    boolean areEqual(java.lang.Object, java.lang.Object);
    void checkNotNull(java.lang.Object);
    void checkNotNull(java.lang.Object, java.lang.String);
    void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    void checkNotNullParameter(java.lang.Object, java.lang.String);
}

-keep,includedescriptorclasses class kotlin.jvm.internal.Lambda {
    <init>(int);
}

-keep,includedescriptorclasses class kotlin.jvm.internal.Ref

-keep,includedescriptorclasses class kotlin.jvm.internal.Ref$ObjectRef {
    java.lang.Object element;
    <init>();
}

-keep,includedescriptorclasses class kotlin.jvm.internal.StringCompanionObject {
    kotlin.jvm.internal.StringCompanionObject INSTANCE;
}

-keep,includedescriptorclasses class kotlin.ranges.IntRange

-keep,includedescriptorclasses class kotlin.ranges.RangesKt

-keep,includedescriptorclasses class kotlin.ranges.RangesKt___RangesKt {
    kotlin.ranges.IntRange until(int, int);
}

-keep,includedescriptorclasses class kotlin.text.Charsets {
    java.nio.charset.Charset US_ASCII;
}

-keep,includedescriptorclasses class kotlin.text.Regex {
    <init>(java.lang.String);
    boolean containsMatchIn(java.lang.CharSequence);
}

-keep,includedescriptorclasses class kotlin.text.StringsKt

-keep,includedescriptorclasses class kotlin.text.StringsKt__StringsJVMKt {
    java.lang.String replace$default(java.lang.String, java.lang.String, java.lang.String, boolean, int, java.lang.Object);
}

-keep,includedescriptorclasses class kotlin.text.StringsKt__StringsKt {
    boolean contains$default(java.lang.CharSequence, java.lang.CharSequence, boolean, int, java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
}

-keep,includedescriptorclasses class kotlin.time.Duration {
    kotlin.time.Duration$Companion Companion;
}

-keep,includedescriptorclasses class kotlin.time.Duration$Companion

-keep,includedescriptorclasses class kotlin.time.DurationKt {
    long toDuration(int, kotlin.time.DurationUnit);
}

-keep,includedescriptorclasses class kotlin.time.DurationUnit {
    kotlin.time.DurationUnit SECONDS;
}

-keep,includedescriptorclasses class kotlinx.coroutines.BuildersKt {
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2);
    kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
    kotlinx.coroutines.Job launch$default(kotlinx.coroutines.CoroutineScope, kotlin.coroutines.CoroutineContext, kotlinx.coroutines.CoroutineStart, kotlin.jvm.functions.Function2, int, java.lang.Object);
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2);
    java.lang.Object runBlocking$default(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, int, java.lang.Object);
    java.lang.Object withContext(kotlin.coroutines.CoroutineContext, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses interface kotlinx.coroutines.CancellableContinuation {
    void resume(java.lang.Object, kotlin.jvm.functions.Function1);
}

-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuationImpl {
    <init>(kotlin.coroutines.Continuation, int);
    java.lang.Object getResult();
    void initCancellability();
}

-keep,includedescriptorclasses interface kotlinx.coroutines.CompletableDeferred {
    boolean complete(java.lang.Object);
}

-keep,includedescriptorclasses class kotlinx.coroutines.CompletableDeferredKt {
    kotlinx.coroutines.CompletableDeferred CompletableDeferred$default(kotlinx.coroutines.Job, int, java.lang.Object);
}

-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineDispatcher

-keep,includedescriptorclasses interface kotlinx.coroutines.CoroutineScope {
    kotlin.coroutines.CoroutineContext getCoroutineContext();
}

-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineStart {
    kotlinx.coroutines.CoroutineStart DEFAULT;
    kotlinx.coroutines.CoroutineStart UNDISPATCHED;
}

-keep,includedescriptorclasses interface kotlinx.coroutines.Deferred {
    java.lang.Object await(kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlinx.coroutines.DelayKt {
    java.lang.Object delay(long, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlinx.coroutines.Dispatchers {
    kotlinx.coroutines.CoroutineDispatcher getIO();
    kotlinx.coroutines.MainCoroutineDispatcher getMain();
    kotlinx.coroutines.CoroutineDispatcher getUnconfined();
}

-keep,includedescriptorclasses class kotlinx.coroutines.ExecutorsKt {
    kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}

-keep,includedescriptorclasses interface kotlinx.coroutines.Job {
    boolean isCancelled();
    boolean isCompleted();
    java.lang.Object join(kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses class kotlinx.coroutines.Job$DefaultImpls {
    void cancel$default(kotlinx.coroutines.Job, java.util.concurrent.CancellationException, int, java.lang.Object);
}

-keep,includedescriptorclasses class kotlinx.coroutines.MainCoroutineDispatcher {
    kotlinx.coroutines.MainCoroutineDispatcher getImmediate();
}

-keep,includedescriptorclasses class kotlinx.coroutines.TimeoutKt {
    java.lang.Object withTimeout(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
    java.lang.Object withTimeout-KLykuaI(long, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses interface kotlinx.coroutines.flow.Flow

-keep,includedescriptorclasses class kotlinx.coroutines.flow.FlowKt {
    java.lang.Object first(kotlinx.coroutines.flow.Flow, kotlin.jvm.functions.Function2, kotlin.coroutines.Continuation);
}

-keep,includedescriptorclasses interface kotlinx.coroutines.flow.MutableStateFlow {
    void setValue(java.lang.Object);
}

-keep,includedescriptorclasses class kotlinx.coroutines.flow.StateFlowKt {
    kotlinx.coroutines.flow.MutableStateFlow MutableStateFlow(java.lang.Object);
}
