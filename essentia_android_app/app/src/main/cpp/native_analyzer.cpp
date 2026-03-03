#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <map>
#include <mutex>
#include <numeric>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <essentia/algorithmfactory.h>
#include <essentia/essentia.h>
#include <essentia/essentiamath.h>

namespace {

using essentia::Real;
using essentia::standard::Algorithm;
using essentia::standard::AlgorithmFactory;

struct ErrorItem {
  std::string algorithm;
  std::string message;
};

struct AlgorithmGuard {
  std::vector<Algorithm*> algorithms;

  Algorithm* add(Algorithm* algo) {
    algorithms.push_back(algo);
    return algo;
  }

  ~AlgorithmGuard() {
    for (Algorithm* algo : algorithms) {
      delete algo;
    }
  }
};

std::once_flag g_essentia_init_once;

void ensureEssentiaInitialized() {
  std::call_once(g_essentia_init_once, []() { essentia::init(); });
}

std::string jsonEscape(const std::string& in) {
  std::ostringstream out;
  for (char c : in) {
    switch (c) {
      case '"':
        out << "\\\"";
        break;
      case '\\':
        out << "\\\\";
        break;
      case '\b':
        out << "\\b";
        break;
      case '\f':
        out << "\\f";
        break;
      case '\n':
        out << "\\n";
        break;
      case '\r':
        out << "\\r";
        break;
      case '\t':
        out << "\\t";
        break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
              << static_cast<int>(static_cast<unsigned char>(c));
        } else {
          out << c;
        }
    }
  }
  return out.str();
}

std::string jsonString(const std::string& in) {
  return std::string("\"") + jsonEscape(in) + "\"";
}

std::string jsonNumber(double value) {
  if (!std::isfinite(value)) return "null";
  std::ostringstream out;
  out << std::fixed << std::setprecision(6) << value;
  std::string s = out.str();
  while (!s.empty() && s.back() == '0') s.pop_back();
  if (!s.empty() && s.back() == '.') s.pop_back();
  if (s.empty()) s = "0";
  return s;
}

std::string jsonNumberArray(const std::vector<Real>& values) {
  std::ostringstream out;
  out << '[';
  for (size_t i = 0; i < values.size(); ++i) {
    if (i) out << ',';
    out << jsonNumber(values[i]);
  }
  out << ']';
  return out.str();
}

std::string jsonObjectFromPairs(const std::vector<std::pair<std::string, std::string>>& pairs) {
  std::ostringstream out;
  out << '{';
  for (size_t i = 0; i < pairs.size(); ++i) {
    if (i) out << ',';
    out << jsonString(pairs[i].first) << ':' << pairs[i].second;
  }
  out << '}';
  return out.str();
}

std::string jsonErrors(const std::vector<ErrorItem>& errors) {
  std::ostringstream out;
  out << '[';
  for (size_t i = 0; i < errors.size(); ++i) {
    if (i) out << ',';
    out << '{'
        << jsonString("algorithm") << ':' << jsonString(errors[i].algorithm) << ','
        << jsonString("message") << ':' << jsonString(errors[i].message)
        << '}';
  }
  out << ']';
  return out.str();
}

Real meanOrZero(const std::vector<Real>& values) {
  if (values.empty()) return 0.0f;
  Real sum = std::accumulate(values.begin(), values.end(), 0.0f);
  return sum / static_cast<Real>(values.size());
}

std::vector<Real> downsampleEnvelope(const std::vector<Real>& signal, int targetPoints) {
  std::vector<Real> output;
  if (signal.empty() || targetPoints <= 0) return output;

  const size_t n = signal.size();
  const size_t bins = std::min(static_cast<size_t>(targetPoints), n);
  if (bins == 0) return output;
  output.reserve(bins);

  for (size_t i = 0; i < bins; ++i) {
    size_t start = (i * n) / bins;
    size_t end = ((i + 1) * n) / bins;
    start = std::min(start, n);
    end = std::min(end, n);
    if (end <= start) {
      output.push_back(0.0f);
      continue;
    }
    Real acc = 0.0f;
    for (size_t j = start; j < end; ++j) {
      acc += std::fabs(signal[j]);
    }
    output.push_back(acc / static_cast<Real>(end - start));
  }
  return output;
}

std::vector<Real> downsampleMean(const std::vector<Real>& values, int targetPoints) {
  std::vector<Real> output;
  if (values.empty() || targetPoints <= 0) return output;

  const size_t n = values.size();
  const size_t bins = std::min(static_cast<size_t>(targetPoints), n);
  if (bins == 0) return output;
  output.reserve(bins);

  for (size_t i = 0; i < bins; ++i) {
    size_t start = (i * n) / bins;
    size_t end = ((i + 1) * n) / bins;
    start = std::min(start, n);
    end = std::min(end, n);
    if (end <= start) {
      output.push_back(0.0f);
      continue;
    }
    Real acc = 0.0f;
    for (size_t j = start; j < end; ++j) {
      acc += values[j];
    }
    output.push_back(acc / static_cast<Real>(end - start));
  }
  return output;
}

void appendError(std::vector<ErrorItem>& errors, const std::string& algorithm, const std::string& message) {
  errors.push_back({algorithm, message});
}

template <typename Fn>
void runSafe(const std::string& algorithm, std::vector<ErrorItem>& errors, Fn&& fn) {
  try {
    fn();
  } catch (const std::exception& e) {
    appendError(errors, algorithm, e.what());
  } catch (...) {
    appendError(errors, algorithm, "Unknown native error");
  }
}

std::string makeErrorJson(const std::string& message) {
  std::vector<std::pair<std::string, std::string>> root{
      {"meta", jsonObjectFromPairs({{"status", jsonString("error")}})},
      {"summary", jsonObjectFromPairs({})},
      {"temporal", jsonObjectFromPairs({})},
      {"spectral", jsonObjectFromPairs({})},
      {"rhythm", jsonObjectFromPairs({})},
      {"tonal", jsonObjectFromPairs({})},
      {"highlevel", jsonObjectFromPairs({})},
      {"stats", jsonObjectFromPairs({})},
      {"series", jsonObjectFromPairs({})},
      {"errors", jsonErrors({{"native", message}})}};
  return jsonObjectFromPairs(root);
}

std::string analyzeSignalToJson(const std::vector<Real>& signal, int sampleRate,
                                const std::string& fileName) {
  if (signal.empty()) {
    return makeErrorJson("Input PCM is empty");
  }
  if (sampleRate <= 0) {
    return makeErrorJson("Invalid sample rate");
  }

  ensureEssentiaInitialized();

  const auto t0 = std::chrono::steady_clock::now();
  std::vector<ErrorItem> errors;

  AlgorithmFactory& factory = AlgorithmFactory::instance();
  AlgorithmGuard guard;

  constexpr int frameSize = 2048;
  constexpr int hopSize = 512;

  Real rms = 0.0f;
  Real energy = 0.0f;
  Real zcr = 0.0f;
  Real loudness = 0.0f;
  Real effectiveDuration = 0.0f;
  Real mean = 0.0f;
  Real variance = 0.0f;
  Real entropy = 0.0f;
  Real crest = 0.0f;
  Real flatness = 0.0f;

  Real dynamicComplexity = 0.0f;
  Real dynamicLoudness = 0.0f;
  Real danceability = 0.0f;
  std::vector<Real> dfa;
  int intensity = 0;

  Real onsetRate = 0.0f;
  std::vector<Real> onsetTimes;

  Real bpm = 0.0f;
  Real rhythmConfidence = 0.0f;
  std::vector<Real> ticks;
  std::vector<Real> bpmIntervals;
  std::vector<Real> bpmEstimates;

  std::string key = "unknown";
  std::string scale = "unknown";
  Real keyStrength = 0.0f;

  std::vector<Real> spectralCentroidSeries;
  std::vector<Real> onsetStrengthSeries;
  std::vector<Real> mfcc0Series;
  std::vector<Real> gfcc0Series;
  std::vector<Real> bfcc0Series;
  std::vector<Real> rolloffSeries;
  std::vector<Real> hfcSeries;
  std::vector<Real> spectralContrast0Series;
  std::vector<Real> hpcp0Series;

  runSafe("RMS", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("RMS"));
    algo->input("array").set(signal);
    algo->output("rms").set(rms);
    algo->compute();
  });

  runSafe("Energy", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Energy"));
    algo->input("array").set(signal);
    algo->output("energy").set(energy);
    algo->compute();
  });

  runSafe("ZeroCrossingRate", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("ZeroCrossingRate"));
    algo->input("signal").set(signal);
    algo->output("zeroCrossingRate").set(zcr);
    algo->compute();
  });

  runSafe("Loudness", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Loudness"));
    algo->input("signal").set(signal);
    algo->output("loudness").set(loudness);
    algo->compute();
  });

  runSafe("EffectiveDuration", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("EffectiveDuration"));
    algo->input("signal").set(signal);
    algo->output("effectiveDuration").set(effectiveDuration);
    algo->compute();
  });

  runSafe("Mean", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Mean"));
    algo->input("array").set(signal);
    algo->output("mean").set(mean);
    algo->compute();
  });

  runSafe("Variance", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Variance"));
    algo->input("array").set(signal);
    algo->output("variance").set(variance);
    algo->compute();
  });

  runSafe("Entropy", errors, [&]() {
    std::vector<Real> nonNegative(signal.size());
    std::transform(signal.begin(), signal.end(), nonNegative.begin(),
                   [](Real x) { return std::fabs(x) + 1e-12f; });
    Algorithm* algo = guard.add(factory.create("Entropy"));
    algo->input("array").set(nonNegative);
    algo->output("entropy").set(entropy);
    algo->compute();
  });

  runSafe("Crest", errors, [&]() {
    std::vector<Real> nonNegative(signal.size());
    std::transform(signal.begin(), signal.end(), nonNegative.begin(),
                   [](Real x) { return std::fabs(x) + 1e-12f; });
    Algorithm* algo = guard.add(factory.create("Crest"));
    algo->input("array").set(nonNegative);
    algo->output("crest").set(crest);
    algo->compute();
  });

  runSafe("Flatness", errors, [&]() {
    std::vector<Real> nonNegative(signal.size());
    std::transform(signal.begin(), signal.end(), nonNegative.begin(),
                   [](Real x) { return std::fabs(x) + 1e-12f; });
    Algorithm* algo = guard.add(factory.create("Flatness"));
    algo->input("array").set(nonNegative);
    algo->output("flatness").set(flatness);
    algo->compute();
  });

  runSafe("DynamicComplexity", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("DynamicComplexity"));
    algo->input("signal").set(signal);
    algo->output("dynamicComplexity").set(dynamicComplexity);
    algo->output("loudness").set(dynamicLoudness);
    algo->compute();
  });

  runSafe("Danceability", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Danceability", "sampleRate", static_cast<Real>(sampleRate)));
    algo->input("signal").set(signal);
    algo->output("danceability").set(danceability);
    algo->output("dfa").set(dfa);
    algo->compute();
  });

  runSafe("Intensity", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("Intensity", "sampleRate", static_cast<Real>(sampleRate)));
    algo->input("signal").set(signal);
    algo->output("intensity").set(intensity);
    algo->compute();
  });

  runSafe("OnsetRate", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("OnsetRate", "sampleRate", static_cast<Real>(sampleRate)));
    algo->input("signal").set(signal);
    algo->output("onsetRate").set(onsetRate);
    algo->output("onsets").set(onsetTimes);
    algo->compute();
  });

  runSafe("RhythmExtractor2013", errors, [&]() {
    Algorithm* algo = guard.add(factory.create("RhythmExtractor2013", "method", "multifeature"));
    algo->input("signal").set(signal);
    algo->output("bpm").set(bpm);
    algo->output("ticks").set(ticks);
    algo->output("confidence").set(rhythmConfidence);
    algo->output("estimates").set(bpmEstimates);
    algo->output("bpmIntervals").set(bpmIntervals);
    algo->compute();
  });

  runSafe("KeyExtractor", errors, [&]() {
    Algorithm* algo = guard.add(factory.create(
        "KeyExtractor", "sampleRate", static_cast<Real>(sampleRate),
        "frameSize", frameSize * 2, "hopSize", frameSize * 2));
    algo->input("audio").set(signal);
    algo->output("key").set(key);
    algo->output("scale").set(scale);
    algo->output("strength").set(keyStrength);
    algo->compute();
  });

  runSafe("FramePipeline", errors, [&]() {
    Algorithm* frameCutter = guard.add(factory.create("FrameCutter", "frameSize", frameSize,
                                                      "hopSize", hopSize,
                                                      "startFromZero", true));
    Algorithm* windowing = guard.add(factory.create("Windowing", "type", "hann"));
    Algorithm* spectrumAlgo = guard.add(factory.create("Spectrum"));
    Algorithm* mfccAlgo = guard.add(factory.create("MFCC"));
    Algorithm* gfccAlgo = guard.add(factory.create("GFCC"));
    Algorithm* bfccAlgo = guard.add(factory.create("BFCC"));
    Algorithm* fluxAlgo = guard.add(factory.create("Flux", "halfRectify", true));
    Algorithm* rolloffAlgo = guard.add(factory.create("RollOff", "sampleRate", static_cast<Real>(sampleRate)));
    Algorithm* hfcAlgo = guard.add(factory.create("HFC"));
    Algorithm* spectralContrastAlgo = guard.add(factory.create("SpectralContrast"));
    Algorithm* spectralPeaksAlgo = guard.add(factory.create("SpectralPeaks",
                                                            "sampleRate", static_cast<Real>(sampleRate),
                                                            "maxPeaks", 60,
                                                            "minFrequency", 40.0,
                                                            "maxFrequency", 5000.0,
                                                            "magnitudeThreshold", 0.00001,
                                                            "orderBy", "frequency"));
    Algorithm* hpcpAlgo = guard.add(factory.create("HPCP", "sampleRate", static_cast<Real>(sampleRate),
                                                    "size", 36,
                                                    "minFrequency", 40.0,
                                                    "maxFrequency", 5000.0,
                                                    "harmonics", 4,
                                                    "weightType", "squaredCosine"));
    Algorithm* centroidAlgo = guard.add(factory.create("Centroid", "range", static_cast<Real>(sampleRate) / 2.0f));

    std::vector<Real> frame;
    std::vector<Real> windowedFrame;
    std::vector<Real> spectrum;

    std::vector<Real> mfccBands;
    std::vector<Real> mfcc;
    std::vector<Real> gfccBands;
    std::vector<Real> gfcc;
    std::vector<Real> bfccBands;
    std::vector<Real> bfcc;
    Real flux = 0.0f;
    Real rolloff = 0.0f;
    Real hfc = 0.0f;
    std::vector<Real> spectralContrast;
    std::vector<Real> spectralValley;
    std::vector<Real> peakFreqs;
    std::vector<Real> peakMags;
    std::vector<Real> hpcp;
    Real centroid = 0.0f;

    frameCutter->input("signal").set(signal);
    frameCutter->output("frame").set(frame);

    windowing->input("frame").set(frame);
    windowing->output("frame").set(windowedFrame);

    spectrumAlgo->input("frame").set(windowedFrame);
    spectrumAlgo->output("spectrum").set(spectrum);

    mfccAlgo->input("spectrum").set(spectrum);
    mfccAlgo->output("bands").set(mfccBands);
    mfccAlgo->output("mfcc").set(mfcc);

    gfccAlgo->input("spectrum").set(spectrum);
    gfccAlgo->output("bands").set(gfccBands);
    gfccAlgo->output("gfcc").set(gfcc);

    bfccAlgo->input("spectrum").set(spectrum);
    bfccAlgo->output("bands").set(bfccBands);
    bfccAlgo->output("bfcc").set(bfcc);

    fluxAlgo->input("spectrum").set(spectrum);
    fluxAlgo->output("flux").set(flux);

    rolloffAlgo->input("spectrum").set(spectrum);
    rolloffAlgo->output("rollOff").set(rolloff);

    hfcAlgo->input("spectrum").set(spectrum);
    hfcAlgo->output("hfc").set(hfc);

    spectralContrastAlgo->input("spectrum").set(spectrum);
    spectralContrastAlgo->output("spectralContrast").set(spectralContrast);
    spectralContrastAlgo->output("spectralValley").set(spectralValley);

    spectralPeaksAlgo->input("spectrum").set(spectrum);
    spectralPeaksAlgo->output("frequencies").set(peakFreqs);
    spectralPeaksAlgo->output("magnitudes").set(peakMags);

    hpcpAlgo->input("frequencies").set(peakFreqs);
    hpcpAlgo->input("magnitudes").set(peakMags);
    hpcpAlgo->output("hpcp").set(hpcp);

    centroidAlgo->input("array").set(spectrum);
    centroidAlgo->output("centroid").set(centroid);

    while (true) {
      frameCutter->compute();
      if (frame.empty()) break;
      if (essentia::isSilent(frame)) continue;

      windowing->compute();
      spectrumAlgo->compute();
      if (spectrum.empty()) continue;

      mfccAlgo->compute();
      gfccAlgo->compute();
      bfccAlgo->compute();
      fluxAlgo->compute();
      rolloffAlgo->compute();
      hfcAlgo->compute();
      spectralContrastAlgo->compute();
      spectralPeaksAlgo->compute();

      if (!peakFreqs.empty() && !peakMags.empty()) {
        hpcpAlgo->compute();
      } else {
        hpcp.clear();
      }

      centroidAlgo->compute();

      if (!mfcc.empty()) mfcc0Series.push_back(mfcc.front());
      if (!gfcc.empty()) gfcc0Series.push_back(gfcc.front());
      if (!bfcc.empty()) bfcc0Series.push_back(bfcc.front());

      rolloffSeries.push_back(rolloff);
      hfcSeries.push_back(hfc);
      onsetStrengthSeries.push_back(std::max<Real>(0.0f, flux));
      spectralCentroidSeries.push_back(centroid);
      if (!spectralContrast.empty()) spectralContrast0Series.push_back(spectralContrast.front());
      if (!hpcp.empty()) hpcp0Series.push_back(hpcp.front());
    }
  });

  std::map<int, int> bpmHistogram;
  if (ticks.size() > 1) {
    for (size_t i = 1; i < ticks.size(); ++i) {
      const Real dt = ticks[i] - ticks[i - 1];
      if (dt > 1e-6f) {
        const int bpmBin = static_cast<int>(std::round(60.0f / dt));
        if (bpmBin > 0 && bpmBin < 400) {
          bpmHistogram[bpmBin]++;
        }
      }
    }
  }

  const std::vector<Real> waveformEnvelope = downsampleEnvelope(signal, 240);
  const std::vector<Real> spectralCentroidSeriesChart = downsampleMean(spectralCentroidSeries, 600);
  const std::vector<Real> onsetStrengthSeriesChart = downsampleMean(onsetStrengthSeries, 600);
  const auto t1 = std::chrono::steady_clock::now();
  const double elapsedMs = std::chrono::duration<double, std::milli>(t1 - t0).count();

  std::vector<std::pair<std::string, std::string>> metaObject = {
      {"fileName", jsonString(fileName)},
      {"originalSampleRate", jsonNumber(sampleRate)},
      {"analysisSampleRate", jsonNumber(sampleRate)},
      {"durationSeconds", jsonNumber(static_cast<double>(signal.size()) / sampleRate)},
      {"processingMs", jsonNumber(elapsedMs)},
      {"algorithmCount", jsonNumber(24)}
  };

  std::vector<std::pair<std::string, std::string>> summaryObject = {
      {"BPM", jsonNumber(bpm)},
      {"Key", jsonString(key + " " + scale)},
      {"Loudness", jsonNumber(dynamicLoudness)},
      {"RMS", jsonNumber(rms)},
      {"SpectralCentroid", jsonNumber(meanOrZero(spectralCentroidSeries))}
  };

  std::vector<std::pair<std::string, std::string>> temporalObject = {
      {"duration", jsonNumber(static_cast<double>(signal.size()) / sampleRate)},
      {"effectiveDuration", jsonNumber(effectiveDuration)},
      {"zeroCrossingRate", jsonNumber(zcr)},
      {"loudness", jsonNumber(loudness)},
      {"dynamicLoudness", jsonNumber(dynamicLoudness)},
      {"onsetRate", jsonNumber(onsetRate)}
  };

  std::vector<std::pair<std::string, std::string>> spectralObject = {
      {"mfcc0Mean", jsonNumber(meanOrZero(mfcc0Series))},
      {"gfcc0Mean", jsonNumber(meanOrZero(gfcc0Series))},
      {"bfcc0Mean", jsonNumber(meanOrZero(bfcc0Series))},
      {"rolloffMean", jsonNumber(meanOrZero(rolloffSeries))},
      {"hfcMean", jsonNumber(meanOrZero(hfcSeries))},
      {"spectralContrast0Mean", jsonNumber(meanOrZero(spectralContrast0Series))},
      {"spectralCentroidMean", jsonNumber(meanOrZero(spectralCentroidSeries))},
      {"fluxMean", jsonNumber(meanOrZero(onsetStrengthSeries))}
  };

  std::vector<std::pair<std::string, std::string>> rhythmObject = {
      {"bpm", jsonNumber(bpm)},
      {"confidence", jsonNumber(rhythmConfidence)},
      {"ticksCount", jsonNumber(static_cast<double>(ticks.size()))},
      {"estimatesCount", jsonNumber(static_cast<double>(bpmEstimates.size()))}
  };

  std::vector<std::pair<std::string, std::string>> tonalObject = {
      {"key", jsonString(key)},
      {"scale", jsonString(scale)},
      {"strength", jsonNumber(keyStrength)},
      {"hpcp0Mean", jsonNumber(meanOrZero(hpcp0Series))}
  };

  std::vector<std::pair<std::string, std::string>> highlevelObject = {
      {"danceability", jsonNumber(danceability)},
      {"dynamicComplexity", jsonNumber(dynamicComplexity)},
      {"intensity", jsonNumber(intensity)},
      {"dfaCount", jsonNumber(static_cast<double>(dfa.size()))}
  };

  std::vector<std::pair<std::string, std::string>> statsObject = {
      {"rms", jsonNumber(rms)},
      {"energy", jsonNumber(energy)},
      {"mean", jsonNumber(mean)},
      {"variance", jsonNumber(variance)},
      {"entropy", jsonNumber(entropy)},
      {"crest", jsonNumber(crest)},
      {"flatness", jsonNumber(flatness)}
  };

  std::ostringstream bpmHistogramJson;
  bpmHistogramJson << '[';
  bool first = true;
  for (const auto& [bpmBin, count] : bpmHistogram) {
    if (!first) bpmHistogramJson << ',';
    first = false;
    bpmHistogramJson << '{'
                     << jsonString("bpm") << ':' << jsonNumber(bpmBin) << ','
                     << jsonString("count") << ':' << jsonNumber(count)
                     << '}';
  }
  bpmHistogramJson << ']';

  std::vector<std::pair<std::string, std::string>> seriesObject = {
      {"waveformEnvelope", jsonNumberArray(waveformEnvelope)},
      {"spectralCentroid", jsonNumberArray(spectralCentroidSeriesChart)},
      {"onsetStrength", jsonNumberArray(onsetStrengthSeriesChart)},
      {"bpmHistogram", bpmHistogramJson.str()}
  };

  std::vector<std::pair<std::string, std::string>> rootObject = {
      {"meta", jsonObjectFromPairs(metaObject)},
      {"summary", jsonObjectFromPairs(summaryObject)},
      {"temporal", jsonObjectFromPairs(temporalObject)},
      {"spectral", jsonObjectFromPairs(spectralObject)},
      {"rhythm", jsonObjectFromPairs(rhythmObject)},
      {"tonal", jsonObjectFromPairs(tonalObject)},
      {"highlevel", jsonObjectFromPairs(highlevelObject)},
      {"stats", jsonObjectFromPairs(statsObject)},
      {"series", jsonObjectFromPairs(seriesObject)},
      {"errors", jsonErrors(errors)}
  };

  return jsonObjectFromPairs(rootObject);
}

std::string jstringToUtf(JNIEnv* env, jstring value) {
  if (value == nullptr) return "unknown";
  const char* raw = env->GetStringUTFChars(value, nullptr);
  if (raw == nullptr) return "unknown";
  std::string out(raw);
  env->ReleaseStringUTFChars(value, raw);
  return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_iriver_essentiaanalyzer_nativebridge_EssentiaNativeBridge_analyzePcmFloat(
    JNIEnv* env, jobject /*thiz*/, jfloatArray pcm_mono, jint sample_rate, jstring file_name) {
  if (pcm_mono == nullptr) {
    return env->NewStringUTF(makeErrorJson("Null PCM input").c_str());
  }

  const jsize length = env->GetArrayLength(pcm_mono);
  std::vector<Real> signal(static_cast<size_t>(length));
  if (length > 0) {
    env->GetFloatArrayRegion(pcm_mono, 0, length, reinterpret_cast<jfloat*>(signal.data()));
  }

  std::string json;
  try {
    json = analyzeSignalToJson(signal, static_cast<int>(sample_rate), jstringToUtf(env, file_name));
  } catch (const std::exception& e) {
    json = makeErrorJson(std::string("Fatal native failure: ") + e.what());
  } catch (...) {
    json = makeErrorJson("Fatal native failure");
  }

  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_iriver_essentiaanalyzer_nativebridge_EssentiaNativeBridge_getNativeInfo(
    JNIEnv* env, jobject /*thiz*/) {
  ensureEssentiaInitialized();
  std::string info = "Essentia JNI ready (arm64-v8a, C++17, frame=2048, hop=512)";
  return env->NewStringUTF(info.c_str());
}
