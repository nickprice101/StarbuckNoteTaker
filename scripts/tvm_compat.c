/*
 * tvm_compat.c — TVM FFI compatibility shim
 *
 * The Llama model library (.o files) was compiled with a newer TVM that uses the
 * TVMFFI* API (post-2024 refactor).  The bundled runtime (libtvm4j_runtime_packed.so
 * from Android-09262024) still exports the older TVMBackend*/TVMFuncCall API.
 *
 * This file bridges the gap by providing thin wrappers for the three renamed symbols
 * so the model .o files link and run correctly against the older runtime.
 *
 * Undefined references resolved here:
 *   TVMFFIEnvModRegisterSystemLibSymbol  → TVMBackendRegisterSystemLibSymbol
 *   TVMFFIFunctionCall                   → TVMFuncCall
 *   TVMFFIErrorSetRaisedFromCStrParts    → TVMAPISetLastError
 */

#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* --------------------------------------------------------------------------
 * TVMValue union — must match tvm/runtime/c_runtime_api.h (stable ABI)
 * -------------------------------------------------------------------------- */
typedef union {
    int64_t  v_int64;
    double   v_float64;
    void    *v_handle;
    const char *v_str;
    int      v_int32;
    int8_t   v_bool;
} TVMValue;

typedef void *TVMFunctionHandle;

/* --------------------------------------------------------------------------
 * Declarations of the old API exported by libtvm4j_runtime_packed.so
 * -------------------------------------------------------------------------- */
extern int  TVMBackendRegisterSystemLibSymbol(const char *name, TVMValue value);
extern void TVMAPISetLastError(const char *msg);
extern int  TVMFuncCall(TVMFunctionHandle func,
                        TVMValue        *arg_values,
                        int             *type_codes,
                        int              num_args,
                        TVMValue        *ret_val,
                        int             *ret_type_code);

/* --------------------------------------------------------------------------
 * New API wrappers
 * -------------------------------------------------------------------------- */

/*
 * TVMFFIEnvModRegisterSystemLibSymbol(name, ptr)
 *
 * In newer TVM the second argument is a raw void* instead of TVMValue.
 * Map it to TVMValue.v_handle and call the old backend function.
 */
__attribute__((visibility("default")))
int TVMFFIEnvModRegisterSystemLibSymbol(const char *name, void *ptr) {
    TVMValue val;
    memset(&val, 0, sizeof(val));
    val.v_handle = ptr;
    return TVMBackendRegisterSystemLibSymbol(name, val);
}

/*
 * TVMFFIFunctionCall — identical signature to TVMFuncCall; direct forward.
 */
__attribute__((visibility("default")))
int TVMFFIFunctionCall(TVMFunctionHandle func,
                       TVMValue        *arg_values,
                       int             *type_codes,
                       int              num_args,
                       TVMValue        *ret_val,
                       int             *ret_type_code) {
    return TVMFuncCall(func, arg_values, type_codes, num_args, ret_val, ret_type_code);
}

/*
 * TVMFFIErrorSetRaisedFromCStrParts(kind, message, backtrace)
 *
 * New TVM splits the error into kind/message/backtrace; old API takes a single
 * string.  Pass the message (most informative part) to TVMAPISetLastError.
 */
__attribute__((visibility("default")))
void TVMFFIErrorSetRaisedFromCStrParts(const char *kind,
                                       const char *message,
                                       const char *backtrace) {
    (void)kind;
    (void)backtrace;
    TVMAPISetLastError(message ? message : "(unknown error)");
}
