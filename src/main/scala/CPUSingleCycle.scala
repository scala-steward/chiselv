package chiselv

import Instruction._
import chisel3._
import chisel3.experimental._
import chisel3.util._

class CPUSingleCycle(
  cpuFrequency: Int,
  bitWidth: Int = 32,
  instructionMemorySize: Int = 1 * 1024,
  dataMemorySize: Int = 1 * 1024,
  memoryFile: String = "",
  numGPIO: Int = 8,
) extends Module {
  val io = IO(new Bundle {
    val led0          = Output(Bool())    // LED 0 is the heartbeat
    val GPIO0External = Analog(numGPIO.W) // GPIO external port
  })

  val stall = WireDefault(false.B)

  // Heartbeat LED
  val blink = Module(new Blinky(cpuFrequency))
  io.led0 := blink.io.led0

  // Instantiate and initialize the Register Bank
  val registerBank = Module(new RegisterBank(bitWidth))
  registerBank.io.regPort.writeEnable := false.B
  registerBank.io.regPort.regwr_data  := DontCare
  registerBank.io.regPort.stall       := stall

  // Instantiate and initialize the Program Counter
  val PC = Module(new ProgramCounter(bitWidth))
  PC.io.pcPort.writeEnable := false.B
  PC.io.pcPort.dataIn      := DontCare
  PC.io.pcPort.writeAdd    := false.B

  // Instantiate and initialize the ALU
  val ALU = Module(new ALU(bitWidth))
  ALU.io.ALUPort.inst := ERR_INST
  ALU.io.ALUPort.a    := DontCare
  ALU.io.ALUPort.b    := DontCare

  // Instantiate and initialize the Instruction Decoder
  val decoder = Module(new Decoder(bitWidth))
  decoder.io.DecoderPort.op := DontCare

  // Instantiate and initialize the Instruction memory
  val instructionMemory = Module(new InstructionMemory(bitWidth, instructionMemorySize, memoryFile))
  instructionMemory.io.memPort.readAddr := DontCare

  // Instantiate and initialize the Memory IO Manager
  val memoryIOManager = Module(new MemoryIOManager(bitWidth, cpuFrequency, dataMemorySize))
  memoryIOManager.io.MemoryIOPort.writeEnable := false.B
  memoryIOManager.io.MemoryIOPort.readAddr    := DontCare
  memoryIOManager.io.MemoryIOPort.writeAddr   := DontCare
  memoryIOManager.io.MemoryIOPort.writeData   := DontCare
  memoryIOManager.io.MemoryIOPort.dataSize    := DontCare
  memoryIOManager.io.MemoryIOPort.writeMask   := DontCare

  // Instantiate and connect GPIO
  val GPIO0 = Module(new GPIO(bitWidth, numGPIO))
  GPIO0.io.GPIOPort <> memoryIOManager.io.GPIO0Port
  GPIO0.io.externalPort <> io.GPIO0External

  // Instantiate and connect the Timer
  val timer0 = Module(new Timer(bitWidth, cpuFrequency))
  timer0.io.timerPort <> memoryIOManager.io.Timer0Port

  // --------------- CPU Control --------------- //
  when(!stall) {
    // Stall the CPU
    PC.io.pcPort.writeEnable := true.B
    PC.io.pcPort.dataIn      := PC.io.pcPort.PC4
  }

  instructionMemory.io.memPort.readAddr := PC.io.pcPort.PC
  registerBank.io.regPort.regwr_addr    := decoder.io.DecoderPort.rd
  registerBank.io.regPort.rs1_addr      := decoder.io.DecoderPort.rs1
  registerBank.io.regPort.rs2_addr      := decoder.io.DecoderPort.rs2
  decoder.io.DecoderPort.op             := instructionMemory.io.memPort.readData

  // ALU Operations
  when(decoder.io.DecoderPort.toALU) {
    ALU.io.ALUPort.inst := decoder.io.DecoderPort.inst
    ALU.io.ALUPort.a    := registerBank.io.regPort.rs1.asUInt
    ALU.io.ALUPort.b := Mux(
      decoder.io.DecoderPort.use_imm,
      decoder.io.DecoderPort.imm.asUInt,
      registerBank.io.regPort.rs2.asUInt,
    )

    registerBank.io.regPort.writeEnable := true.B
    registerBank.io.regPort.regwr_data  := ALU.io.ALUPort.x.asSInt
  }

  // Branch Operations
  when(decoder.io.DecoderPort.branch) {

    ALU.io.ALUPort.a := registerBank.io.regPort.rs1.asUInt
    ALU.io.ALUPort.b := registerBank.io.regPort.rs2.asUInt
    switch(decoder.io.DecoderPort.inst) {
      is(BEQ)(ALU.io.ALUPort.inst  := EQ)
      is(BNE)(ALU.io.ALUPort.inst  := NEQ)
      is(BLT)(ALU.io.ALUPort.inst  := SLT)
      is(BGE)(ALU.io.ALUPort.inst  := GTE)
      is(BLTU)(ALU.io.ALUPort.inst := SLTU)
      is(BGEU)(ALU.io.ALUPort.inst := GTEU)
    }
    when(ALU.io.ALUPort.x === 1.U) {
      PC.io.pcPort.writeEnable := true.B
      PC.io.pcPort.writeAdd    := true.B
      PC.io.pcPort.dataIn      := decoder.io.DecoderPort.imm
    }
  }
  // Jump Operations
  when(decoder.io.DecoderPort.jump) {
    // Write next instruction address to rd
    registerBank.io.regPort.writeEnable := true.B
    ALU.io.ALUPort.inst                 := ADD
    ALU.io.ALUPort.a                    := PC.io.pcPort.PC
    ALU.io.ALUPort.b                    := 4.U
    registerBank.io.regPort.regwr_data  := ALU.io.ALUPort.x.asSInt
    PC.io.pcPort.writeEnable            := true.B

    when(decoder.io.DecoderPort.inst === JAL) {
      // Set PC to jump address
      PC.io.pcPort.writeAdd := true.B
      PC.io.pcPort.dataIn   := decoder.io.DecoderPort.imm
    }
    when(decoder.io.DecoderPort.inst === JALR) {
      // Set PC to jump address
      PC.io.pcPort.dataIn := Cat(
        (registerBank.io.regPort.rs1 + decoder.io.DecoderPort.imm.asSInt).asUInt()(31, 1),
        0.U,
      )
    }
  }

  // LUI
  when(decoder.io.DecoderPort.inst === LUI) {
    registerBank.io.regPort.writeEnable := true.B
    registerBank.io.regPort.regwr_data  := Cat(decoder.io.DecoderPort.imm(31, 12), Fill(12, 0.U)).asSInt
  }

  // AUIPC
  when(decoder.io.DecoderPort.inst === AUIPC) {
    registerBank.io.regPort.writeEnable := true.B
    ALU.io.ALUPort.inst                 := ADD
    ALU.io.ALUPort.a                    := PC.io.pcPort.PC
    ALU.io.ALUPort.b := Cat(
      decoder.io.DecoderPort.imm(31, 12),
      Fill(12, 0.U),
    )
    registerBank.io.regPort.regwr_data := ALU.io.ALUPort.x.asSInt
  }

  // Stores
  when(decoder.io.DecoderPort.is_store) {
    ALU.io.ALUPort.inst := ADD
    ALU.io.ALUPort.a    := registerBank.io.regPort.rs1.asUInt
    ALU.io.ALUPort.b    := decoder.io.DecoderPort.imm
    val memAddr = ALU.io.ALUPort.x

    // What a dirty hack, get rid of this as soon as possible
    when(memAddr(31, 16) === 0x8000L.U || memAddr(31, 16) === 0x8000L.U) {
      // Stall for 1 cycle for sync memory write
      val memValid = RegInit(1.U(1.W))
      memValid := Mux(memValid === 0.U, 1.U, memValid - 1.U)
      stall    := memValid >= 1.U
    }

    when(!stall) {
      memoryIOManager.io.MemoryIOPort.writeAddr := memAddr
      memoryIOManager.io.MemoryIOPort.readAddr  := memAddr

      // Set register and memory addresses, write enable the data memory
      memoryIOManager.io.MemoryIOPort.writeEnable := true.B

      val dataOut  = WireDefault(0.U(32.W))
      val dataSize = WireDefault(0.U(2.W)) // Data size, 1 = byte, 2 = halfword, 3 = word

      // Store Word
      when(decoder.io.DecoderPort.inst === SW) {
        dataOut  := registerBank.io.regPort.rs2.asUInt
        dataSize := 3.U
      }
      // Store Halfword
      when(decoder.io.DecoderPort.inst === SH) {
        dataOut  := Cat(Fill(16, 0.U), registerBank.io.regPort.rs2(15, 0).asUInt)
        dataSize := 2.U
      }
      // Store Byte
      when(decoder.io.DecoderPort.inst === SB) {
        dataOut  := Cat(Fill(24, 0.U), registerBank.io.regPort.rs2(7, 0).asUInt)
        dataSize := 1.U
      }
      memoryIOManager.io.MemoryIOPort.dataSize  := dataSize
      memoryIOManager.io.MemoryIOPort.writeData := dataOut
    }
  }

  // Loads
  when(decoder.io.DecoderPort.is_load) {
    // Set register and memory addresses
    ALU.io.ALUPort.inst := ADD
    ALU.io.ALUPort.a    := registerBank.io.regPort.rs1.asUInt
    ALU.io.ALUPort.b    := decoder.io.DecoderPort.imm
    val memAddr = ALU.io.ALUPort.x

    // What a dirty hack, get rid of this as soon as possible
    when(memAddr(31, 16) === 0x8000L.U || memAddr(31, 16) === 0x8000L.U) {
      // Stall for 1 cycle for sync memory read
      val memValid = RegInit(1.U(1.W))
      memValid := Mux(memValid === 0.U, 1.U, memValid - 1.U)
      stall    := memValid >= 1.U
    }

    memoryIOManager.io.MemoryIOPort.readAddr := memAddr

    when(!stall) {
      registerBank.io.regPort.writeEnable := true.B
      // Load Word
      when(decoder.io.DecoderPort.inst === LW) {
        registerBank.io.regPort.regwr_data := memoryIOManager.io.MemoryIOPort.readData.asSInt
      }
      // Load Halfword
      when(decoder.io.DecoderPort.inst === LH) {
        registerBank.io.regPort.regwr_data := Cat(
          Fill(16, memoryIOManager.io.MemoryIOPort.readData(15)),
          memoryIOManager.io.MemoryIOPort.readData(15, 0),
        ).asSInt

      }
      // Load Halfword Unsigned
      when(decoder.io.DecoderPort.inst === LHU) {
        registerBank.io.regPort.regwr_data := Cat(Fill(16, 0.U), memoryIOManager.io.MemoryIOPort.readData(15, 0)).asSInt
      }
      // Load Byte
      when(decoder.io.DecoderPort.inst === LB) {
        registerBank.io.regPort.regwr_data := Cat(
          Fill(24, memoryIOManager.io.MemoryIOPort.readData(7)),
          memoryIOManager.io.MemoryIOPort.readData(7, 0),
        ).asSInt
      }
      // Load Byte Unsigned
      when(decoder.io.DecoderPort.inst === LBU) {
        registerBank.io.regPort.regwr_data := Cat(Fill(24, 0.U), memoryIOManager.io.MemoryIOPort.readData(7, 0)).asSInt
      }
    }
  }
}
