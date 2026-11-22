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
