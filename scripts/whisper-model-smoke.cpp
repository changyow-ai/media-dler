// Host-only smoke helper. Build against the app's exact whisper.cpp submodule, not system whisper.
// Arguments: model.ggml mono-float32-16k.pcm language-or-auto output.txt
#include <fstream>
#include <string>
#include <vector>
#include "whisper.h"

int main(int argc, char** argv) {
    if (argc != 5) return 64;
    std::ifstream in(argv[2], std::ios::binary | std::ios::ate);
    if (!in) return 65;
    auto length = in.tellg();
    if (length <= 0 || length % 4) return 66;
    std::vector<float> samples(size_t(length) / 4);
    in.seekg(0);
    in.read(reinterpret_cast<char*>(samples.data()), length);
    if (!in) return 66;
    auto cp = whisper_context_default_params();
    cp.use_gpu = false;
    auto* ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) return 67;
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = 2;
    p.translate = false;
    p.no_timestamps = true;
    p.print_special = p.print_progress = p.print_realtime = p.print_timestamps = false;
    p.language = argv[3];
    p.detect_language = false;
    if (std::string(argv[3]) == "zh") p.initial_prompt = "以下是一段中文內容，包含標點符號。";
    int rc = whisper_full(ctx, p, samples.data(), samples.size());
    if (rc) { whisper_free(ctx); return 68; }
    std::ofstream out(argv[4]);
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) out << whisper_full_get_segment_text(ctx, i);
    out.close();
    whisper_free(ctx);
    return out.fail() ? 69 : 0;
}
