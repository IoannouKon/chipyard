package chipyard

import org.chipsalliance.cde.config.Config

// Hardware geometry and numeric format are defined in LUMIXED/Config.scala.
class LUMIXEDRocketConfig extends Config(
  new LUMIXED_PACKAGE.WithLUMIXEDAccelerator ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.rocket.WithNMedCores(1) ++
  new chipyard.config.AbstractConfig)

