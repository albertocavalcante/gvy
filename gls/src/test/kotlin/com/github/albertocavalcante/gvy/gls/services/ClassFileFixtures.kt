// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Hand-built class file headers: everything up to and including `this_class`, which is all the reader consumes. */
internal object ClassFileFixtures {
    const val ACC_PUBLIC_SUPER = 0x0021
    const val ACC_SYNTHETIC = 0x1000
    const val ACC_MODULE = 0x8000

    fun realClass(resource: String): ByteArray =
        checkNotNull(ClassLoader.getSystemResourceAsStream(resource)) { "no $resource" }.use { it.readBytes() }

    /** Pool slots: 1 = Utf8 [internalName], 2 = Class #1. */
    fun minimal(internalName: String, flags: Int = ACC_PUBLIC_SUPER): ByteArray = build(flags, thisClass = 2) { pool ->
        pool.utf8(internalName)
        pool.classRef(1)
    }

    /** One entry of every tag of JVMS §4.4 Table 4.4-B, then optionally a wide constant in the last slots. */
    fun withEveryTag(internalName: String, flags: Int = ACC_PUBLIC_SUPER, lastWide: Wide? = null): ByteArray =
        build(flags, thisClass = 2) { pool ->
            pool.utf8(internalName) // 1
            pool.classRef(1) // 2
            pool.entry(3) { writeInt(1) } // Integer
            pool.entry(4) { writeFloat(1f) } // Float
            pool.wide(5) // Long, slots 5-6
            pool.wide(6) // Double, slots 7-8
            pool.entry(8) { writeShort(1) } // String, slot 9
            pool.entry(9) { writeShort(2).also { writeShort(11) } } // Fieldref, slot 10
            pool.entry(12) { writeShort(1).also { writeShort(1) } } // NameAndType, slot 11
            pool.entry(10) { writeShort(2).also { writeShort(11) } } // Methodref, slot 12
            pool.entry(11) { writeShort(2).also { writeShort(11) } } // InterfaceMethodref, slot 13
            pool.entry(15) { writeByte(1).also { writeShort(12) } } // MethodHandle, slot 14
            pool.entry(16) { writeShort(1) } // MethodType, slot 15
            pool.entry(17) { writeShort(0).also { writeShort(11) } } // Dynamic, slot 16
            pool.entry(18) { writeShort(0).also { writeShort(11) } } // InvokeDynamic, slot 17
            pool.entry(19) { writeShort(1) } // Module, slot 18
            pool.entry(20) { writeShort(1) } // Package, slot 19
            pool.utf8("decoy") // 20
            lastWide?.let { pool.wide(it.tag) } // slots 21-22
        }

    enum class Wide(val tag: Int) { LONG(5), DOUBLE(6) }

    /** A class file whose constant pool starts with a Utf8 entry of the declared [utf8Length] and then runs on. */
    fun headerWithUtf8(utf8Length: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(MAJOR)
            writeShort(2) // one entry
            writeByte(1)
            writeShort(utf8Length)
        }
    }.toByteArray()

    class Pool {
        private val body = ByteArrayOutputStream()
        private val out = DataOutputStream(body)
        var slots = 1
            private set

        fun utf8(value: String) {
            out.writeByte(1)
            out.writeUTF(value)
            slots++
        }

        fun classRef(nameIndex: Int) {
            out.writeByte(7)
            out.writeShort(nameIndex)
            slots++
        }

        fun entry(tag: Int, payload: DataOutputStream.() -> Unit) {
            out.writeByte(tag)
            out.payload()
            slots++
        }

        fun wide(tag: Int) {
            out.writeByte(tag)
            out.writeLong(0)
            slots += 2
        }

        fun bytes(): ByteArray = body.toByteArray()
    }

    private const val MAJOR = 61

    private fun build(flags: Int, thisClass: Int, pool: (Pool) -> Unit): ByteArray {
        val entries = Pool().also(pool)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(0xCAFEBABE.toInt())
                writeShort(0)
                writeShort(MAJOR)
                writeShort(entries.slots)
                write(entries.bytes())
                writeShort(flags)
                writeShort(thisClass)
            }
        }.toByteArray()
    }
}
