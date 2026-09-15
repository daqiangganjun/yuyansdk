#include <jni.h>
#include <android/log.h>
#include <rime_api.h>
#include <rime_levers_api.h>
#include <rime/key_event.h>
#include "predict_db.h"
#include <algorithm>
#include <atomic>
#include <codecvt>
#include <cstring>
#include <fstream>
#include <locale>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();
extern void rime_require_module_levers();

namespace {
std::recursive_mutex engine_mutex;
RimeApi* api = nullptr;
RimeSessionId session = 0;
bool initialized = false;
std::atomic<bool> deployment_failed{false};
std::string shared_dir, user_dir, log_dir, last_error;
std::unique_ptr<rime::PredictDb> predict_db;
std::vector<std::string> associations;

// JNI 的 Modified UTF-8 不支持直接传入四字节 emoji，统一经 UTF-16 边界转换。
jstring java_string(JNIEnv* env, const std::string& text) {
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> converter;
    auto utf16 = converter.from_bytes(text);
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()), utf16.size());
}
std::string utf8(JNIEnv* env, jstring text) {
    if (!text) return {};
    auto chars = env->GetStringChars(text, nullptr);
    if (!chars) return {};
    std::u16string value(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(text));
    env->ReleaseStringChars(text, chars);
    return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>().to_bytes(value);
}

template<typename T, typename F> T protect(T fallback, F action) {
    std::lock_guard<std::recursive_mutex> lock(engine_mutex);
    try { return action(); }
    catch (const std::exception& error) {
        last_error = error.what();
        __android_log_print(ANDROID_LOG_ERROR, "SelfOptRime", "%s", error.what());
    } catch (...) { last_error = "Unknown native engine error"; }
    return fallback;
}
template<typename F> void protect_void(F action) {
    protect(false, [&] { action(); return true; });
}
struct JavaType { jclass cls; jmethodID constructor; };
JavaType candidate_type{}, composition_type{}, menu_type{}, context_type{}, status_type{}, commit_type{};
jclass string_type = nullptr;
bool bind(JNIEnv* env, JavaType& type, const char* name, const char* signature) {
    auto local = env->FindClass(name);
    if (!local) return false;
    type.cls = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    type.constructor = env->GetMethodID(type.cls, "<init>", signature);
    return type.constructor != nullptr;
}

void shutdown() {
    predict_db.reset();
    associations.clear();
    if (session) api->destroy_session(session);
    session = 0;
    if (initialized) api->finalize();
    initialized = false;
}
bool initialize(const std::string& shared, const std::string& user) {
    if (initialized && shared_dir == shared && user_dir == user) return true;
    shutdown();
    shared_dir = shared;
    user_dir = user;
    log_dir = user + "/logs";
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_dir.c_str();
    traits.user_data_dir = user_dir.c_str();
    traits.log_dir = log_dir.c_str();
    traits.min_log_level = 2;
    traits.app_name = "rime.selfopt";
    traits.distribution_name = "SelfOpt";
    traits.distribution_code_name = "selfopt";
    traits.distribution_version = "1";
    if (!api) {
        rime_require_module_lua();
        rime_require_module_octagram();
        rime_require_module_predict();
        rime_require_module_levers();
        api = rime_get_api();
        api->setup(&traits);
        api->set_notification_handler([](void*, RimeSessionId, const char* type, const char* value) {
            if (type && value && std::strcmp(type, "deploy") == 0 && std::strcmp(value, "failure") == 0)
                deployment_failed = true;
        }, nullptr);
    }
    api->initialize(&traits);
    initialized = true;
    return true;
}

bool prepare_predictions() {
    predict_db = std::make_unique<rime::PredictDb>(rime::path(user_dir + "/predict.db"));
    if (predict_db->Load()) return true;
    std::ifstream source(shared_dir + "/predict.tsv");
    if (!source) { last_error = "Prediction source is missing"; return false; }
    rime::predict::RawData data;
    std::string key, value;
    double weight;
    while (source >> key >> value >> weight) data[key].push_back({value, weight});
    if (data.empty() || !predict_db->Build(data) || !predict_db->Save()) {
        last_error = "Failed to build prediction dictionary";
        return false;
    }
    return predict_db->Load();
}

jobjectArray strings(JNIEnv* env, const std::vector<std::string>& values) {
    auto result = env->NewObjectArray(values.size(), string_type, nullptr);
    for (size_t i = 0; result && i < values.size(); ++i) {
        auto value = java_string(env, values[i]);
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto string_class = env->FindClass("java/lang/String");
    string_type = static_cast<jclass>(env->NewGlobalRef(string_class));
    env->DeleteLocalRef(string_class);
    if (!bind(env, candidate_type, "com/yuyan/inputmethod/core/CandidateListItem", "(Ljava/lang/String;Ljava/lang/String;)V") ||
        !bind(env, composition_type, "com/yuyan/inputmethod/core/RimeComposition", "(IIIILjava/lang/String;)V") ||
        !bind(env, menu_type, "com/yuyan/inputmethod/core/RimeMenu", "(IIZII[Lcom/yuyan/inputmethod/core/CandidateListItem;)V") ||
        !bind(env, context_type, "com/yuyan/inputmethod/core/RimeContext", "(Lcom/yuyan/inputmethod/core/RimeComposition;Lcom/yuyan/inputmethod/core/RimeMenu;Ljava/lang/String;[Ljava/lang/String;)V") ||
        !bind(env, status_type, "com/yuyan/inputmethod/core/RimeStatus", "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V") ||
        !bind(env, commit_type, "com/yuyan/inputmethod/core/RimeCommit", "(Ljava/lang/String;)V")) return JNI_ERR;
    return JNI_VERSION_1_6;
}

#define JNI_RIME(name) extern "C" JNIEXPORT name JNICALL
#define METHOD(name) Java_com_yuyan_inputmethod_core_Rime_##name

JNI_RIME(jboolean) METHOD(startupRime)(JNIEnv* env, jclass, jobject, jstring shared, jstring user, jboolean deploy) {
    return protect<jboolean>(false, [&]() -> jboolean {
        last_error.clear();
        if (!initialize(utf8(env, shared), utf8(env, user))) return false;
        if (deploy) {
            deployment_failed = false;
            if (api->start_maintenance(true)) api->join_maintenance_thread();
            if (deployment_failed) { last_error = "Dictionary deployment failed; see the engine log"; return false; }
        }
        if (!session) session = api->create_session();
        if (!session) { last_error = "Failed to create Rime session"; return false; }
        if (!predict_db && !prepare_predictions()) return false;
        return true;
    });
}
JNI_RIME(void) METHOD(exitRime)(JNIEnv*, jclass) { protect_void([] { shutdown(); }); }
JNI_RIME(jstring) METHOD(getLastError)(JNIEnv* env, jclass) {
    return protect<jstring>(nullptr, [&] { return java_string(env, last_error); });
}
JNI_RIME(jboolean) METHOD(migrateUserData)(JNIEnv* env, jclass, jstring shared, jstring user) {
    return protect<jboolean>(false, [&]() -> jboolean {
        const auto shared_path = utf8(env, shared);
        const auto target_path = utf8(env, user);
        initialize(shared_path, target_path + "/legacy");
        auto module = api->find_module("levers");
        if (!module || !module->get_api) { last_error = "Levers API is unavailable"; shutdown(); return false; }
        auto levers = reinterpret_cast<RimeLeversApi*>(module->get_api());
        std::vector<std::pair<std::string, int>> exports;
        for (auto source : {"pinyin", "english", "stroke"}) {
            auto db = user_dir + "/" + source + ".userdb/CURRENT";
            if (!std::ifstream(db)) continue;
            auto file = target_path + "/" + source + ".export.txt";
            const auto exported = levers->export_user_dict(source, file.c_str());
            if (exported < 0) {
                last_error = std::string("Failed to export legacy dictionary: ") + source;
                shutdown(); return false;
            }
            exports.emplace_back(source, exported);
        }
        shutdown();
        initialize(shared_path, target_path);
        for (const auto& [source, exported] : exports) {
            auto file = target_path + "/" + source + ".export.txt";
            std::vector<std::string> targets = source == "pinyin"
                ? std::vector<std::string>{"rime_frost", "rime_ice"}
                : std::vector<std::string>{source == "english" ? "melt_eng" : "stroke"};
            for (const auto& target : targets) {
                if (levers->import_user_dict(target.c_str(), file.c_str()) != exported) {
                    last_error = "User dictionary migration count mismatch: " + target;
                    shutdown(); return false;
                }
            }
        }
        shutdown();
        return true;
    });
}
JNI_RIME(jboolean) METHOD(processRimeKey)(JNIEnv*, jclass, jint key, jint mask) {
    return protect<jboolean>(false, [&] { return session && api->process_key(session, key, mask); });
}
JNI_RIME(void) METHOD(clearRimeComposition)(JNIEnv*, jclass) {
    protect_void([] { if (session) api->clear_composition(session); associations.clear(); });
}
JNI_RIME(jboolean) METHOD(replaceRimeKey)(JNIEnv* env, jclass, jint start, jint length, jstring key) {
    return protect<jboolean>(false, [&]() -> jboolean {
        if (!session || start < 0 || length < 0) return false;
        const auto* current = api->get_input(session);
        std::string input = current ? current : "";
        if (static_cast<size_t>(start + length) > input.size()) return false;
        input.replace(start, length, utf8(env, key));
        return api->set_input(session, input.c_str());
    });
}
JNI_RIME(jstring) METHOD(getRawInput)(JNIEnv* env, jclass) {
    return protect<jstring>(nullptr, [&] {
        auto value = session ? api->get_input(session) : nullptr;
        return java_string(env, value ? value : "");
    });
}
JNI_RIME(jboolean) METHOD(selectRimeCandidate)(JNIEnv*, jclass, jint index) {
    return protect<jboolean>(false, [&] { return session && index >= 0 && api->select_candidate(session, index); });
}
JNI_RIME(void) METHOD(setRimeOption)(JNIEnv* env, jclass, jstring option, jboolean value) {
    protect_void([&] { if (session) api->set_option(session, utf8(env, option).c_str(), value); });
}
JNI_RIME(jboolean) METHOD(selectRimeSchema)(JNIEnv* env, jclass, jstring schema) {
    return protect<jboolean>(false, [&] { return session && api->select_schema(session, utf8(env, schema).c_str()); });
}
JNI_RIME(jboolean) METHOD(validateRimeSchema)(JNIEnv*, jclass) {
    return protect<jboolean>(false, [&]() -> jboolean {
        if (!session) return false;
        char schema[256] = {};
        api->get_current_schema(session, schema, sizeof(schema));
        auto probe = api->create_session();
        if (!probe) return false;
        bool usable = false;
        if (api->select_schema(probe, schema)) {
            api->set_option(probe, "ascii_mode", false);
            const auto input = std::string(schema) == "selfopt_stroke" ? "h" : "ni";
            for (const char* key = input; *key; ++key) api->process_key(probe, *key, 0);
            RIME_STRUCT(RimeContext, result);
            if (api->get_context(probe, &result)) {
                usable = result.menu.num_candidates > 0;
                api->free_context(&result);
            }
        }
        api->destroy_session(probe);
        if (!usable) last_error = "The selected schema cannot load its dictionary";
        return usable;
    });
}
JNI_RIME(jstring) METHOD(getCurrentRimeSchema)(JNIEnv* env, jclass) {
    return protect<jstring>(nullptr, [&] {
        char id[256] = {};
        if (session) api->get_current_schema(session, id, sizeof(id));
        return java_string(env, id);
    });
}
JNI_RIME(void) METHOD(setRimePageSize)(JNIEnv*, jclass, jint size) {
    protect_void([&] {
        if (!session) return;
        char id[256] = {};
        api->get_current_schema(session, id, sizeof(id));
        RimeConfig config{};
        if (api->schema_open(id, &config)) {
            api->config_set_int(&config, "menu/page_size", std::clamp(size, 1, 100));
            api->config_close(&config);
        }
    });
}
JNI_RIME(jint) METHOD(getRimeKeycodeByName)(JNIEnv* env, jclass, jstring name) {
    return protect<jint>(0, [&] { return rime::KeyEvent(utf8(env, name)).keycode(); });
}
JNI_RIME(jobject) METHOD(getRimeCommit)(JNIEnv* env, jclass) {
    return protect<jobject>(nullptr, [&]() -> jobject {
        RIME_STRUCT(RimeCommit, commit);
        if (!session || !api->get_commit(session, &commit)) return nullptr;
        std::unique_ptr<RimeCommit, void(*)(RimeCommit*)> cleanup(&commit, [](RimeCommit* value) { api->free_commit(value); });
        auto text = java_string(env, commit.text ? commit.text : "");
        auto result = env->NewObject(commit_type.cls, commit_type.constructor, text);
        env->DeleteLocalRef(text);
        return result;
    });
}
JNI_RIME(jobject) METHOD(getRimeStatus)(JNIEnv* env, jclass) {
    return protect<jobject>(nullptr, [&]() -> jobject {
        RIME_STRUCT(RimeStatus, status);
        if (!session || !api->get_status(session, &status)) return nullptr;
        std::unique_ptr<RimeStatus, void(*)(RimeStatus*)> cleanup(&status, [](RimeStatus* value) { api->free_status(value); });
        auto id = java_string(env, status.schema_id ? status.schema_id : "");
        auto name = java_string(env, status.schema_name ? status.schema_name : "");
        auto result = env->NewObject(status_type.cls, status_type.constructor, id, name,
            status.is_disabled, status.is_composing, status.is_ascii_mode, status.is_full_shape,
            status.is_simplified, status.is_traditional, status.is_ascii_punct);
        env->DeleteLocalRef(id); env->DeleteLocalRef(name);
        return result;
    });
}
JNI_RIME(jobject) METHOD(getRimeContext)(JNIEnv* env, jclass) {
    return protect<jobject>(nullptr, [&]() -> jobject {
        RIME_STRUCT(RimeContext, context);
        if (!session || !api->get_context(session, &context)) return nullptr;
        std::unique_ptr<RimeContext, void(*)(RimeContext*)> cleanup(&context, [](RimeContext* value) { api->free_context(value); });
        auto& comp = context.composition;
        std::string preedit = comp.preedit ? comp.preedit : "";
        std::replace(preedit.begin(), preedit.end(), ' ', '\'');
        auto text = java_string(env, preedit);
        // Rime 的位置单位是 UTF-8 字节，Java UI 的位置单位是 UTF-16。
        auto position = [&](int byte) {
            auto prefix = java_string(env, preedit.substr(0, std::min<size_t>(std::max(byte, 0), preedit.size())));
            int length = env->GetStringLength(prefix); env->DeleteLocalRef(prefix); return length;
        };
        auto composition = env->NewObject(composition_type.cls, composition_type.constructor,
            env->GetStringLength(text), position(comp.cursor_pos), position(comp.sel_start), position(comp.sel_end), text);
        auto& menu = context.menu;
        auto candidates = env->NewObjectArray(menu.num_candidates, candidate_type.cls, nullptr);
        for (int i = 0; candidates && i < menu.num_candidates; ++i) {
            auto comment = java_string(env, menu.candidates[i].comment ? menu.candidates[i].comment : "");
            auto word = java_string(env, menu.candidates[i].text ? menu.candidates[i].text : "");
            auto item = env->NewObject(candidate_type.cls, candidate_type.constructor, comment, word);
            env->SetObjectArrayElement(candidates, i, item);
            env->DeleteLocalRef(comment); env->DeleteLocalRef(word); env->DeleteLocalRef(item);
        }
        auto java_menu = env->NewObject(menu_type.cls, menu_type.constructor, menu.page_size, menu.page_no,
            menu.is_last_page, menu.highlighted_candidate_index, menu.num_candidates, candidates);
        auto preview = java_string(env, context.commit_text_preview ? context.commit_text_preview : "");
        auto labels = strings(env, {});
        auto result = env->NewObject(context_type.cls, context_type.constructor, composition, java_menu, preview, labels);
        for (auto ref : {static_cast<jobject>(text), composition, static_cast<jobject>(candidates), java_menu,
                         static_cast<jobject>(preview), static_cast<jobject>(labels)}) env->DeleteLocalRef(ref);
        return result;
    });
}
JNI_RIME(jobjectArray) METHOD(getRimeAssociateList)(JNIEnv* env, jclass, jstring query) {
    return protect<jobjectArray>(nullptr, [&] {
        associations.clear();
        auto text = utf8(env, query);
        if (session && predict_db) {
            // 查询由宿主光标提供的上下文，不依赖引擎提交历史，避免切换输入框后串词。
            for (size_t offset = 0; offset < text.size() && associations.empty(); ++offset) {
                if ((static_cast<unsigned char>(text[offset]) & 0xc0) == 0x80) continue;
                if (auto found = predict_db->Lookup(text.substr(offset))) {
                    for (size_t i = 0; i < found->size && i < 50; ++i)
                        associations.push_back(predict_db->GetEntryText(found->at[i]));
                }
            }
        }
        return strings(env, associations);
    });
}
JNI_RIME(jboolean) METHOD(selectRimeAssociate)(JNIEnv*, jclass, jint index) {
    return protect<jboolean>(false, [&] { return index >= 0 && static_cast<size_t>(index) < associations.size(); });
}
