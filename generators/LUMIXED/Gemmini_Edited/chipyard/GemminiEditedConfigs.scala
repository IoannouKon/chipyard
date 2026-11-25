package chipyard
import org.chipsalliance.cde.config.Config

// Existing Gemmini configurations retain GEMMINI_EDITED=false by default.
class EditedGemmini4RocketConfig extends Config(
  new gemmini.GemminiEditedConfig(GEMMINI_EDITED = true, dimension = 4) ++
  new chipyard.RocketConfig)
class EditedGemmini8RocketConfig extends Config(
  new gemmini.GemminiEditedConfig(GEMMINI_EDITED = true, dimension = 8) ++
  new chipyard.RocketConfig)
class StockGemminiFP32RocketConfig extends Config(
  new gemmini.GemminiEditedConfig(GEMMINI_EDITED = false) ++
  new chipyard.RocketConfig)

// Archived 4x4/W8 baseline settings, with genuine FP32 activations and ID8.
class EditedGemmini4BaselineRocketConfig extends Config(
  new gemmini.GemminiEditedBaseline4FP32Config ++
  new chipyard.RocketConfig)

class EditedGemmini8BaselineRocketConfig extends Config(
  new gemmini.GemminiEditedBaseline8FP32Config ++
  new chipyard.RocketConfig)
