package com.example

import com.example.core.cpp.CppToolchain
import com.example.core.python.PythonLexer
import com.example.core.python.PythonParser
import com.example.core.shell.ShellParser
import com.example.core.terminal.AnsiTerminalEmulator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExampleUnitTest {

    @Test
    fun `ansi terminal emulator renders colors and alternate screen`() {
        val term = AnsiTerminalEmulator(40, 10)
        term.write("\u001B[32mHello\u001B[0m\r\nWorld")
        assertTrue(term.getPlainText().contains("Hello\nWorld"))

        term.write("\u001B[?1049hAltMode")
        assertTrue(term.snapshot.value.isAlternateScreen)
        assertTrue(term.getPlainText().contains("AltMode"))

        term.write("\u001B[?1049l")
        assertTrue(!term.snapshot.value.isAlternateScreen)
        assertTrue(term.getPlainText().contains("Hello"))
    }

    @Test
    fun `cpp20 compiler and runtime executes vectors algorithms and recursion`() = runTest {
        val tmpDir = Files.createTempDirectory("termux_cpp_test").toFile()
        try {
            val src = File(tmpDir, "main.cpp")
            src.writeText(
                """
                #include <iostream>
                #include <vector>
                #include <algorithm>
                #include <cmath>
                using namespace std;

                int fact(int n) {
                    if (n <= 1) return 1;
                    return n * fact(n - 1);
                }

                int main() {
                    vector<int> v = {9, 2, 7, 1};
                    sort(v.begin(), v.end());
                    for (auto x : v) cout << x << " ";
                    cout << "| fact(5)=" << fact(5) << " | sqrt(81)=" << sqrt(81) << endl;
                    return 0;
                }
                """.trimIndent()
            )
            val outBin = File(tmpDir, "main")
            val compileOut = StringBuilder()
            val cCode = CppToolchain.compile(listOf(src), outBin, tmpDir) { compileOut.append(it) }
            assertEquals("Compile errors: $compileOut", 0, cCode)

            val runOut = StringBuilder()
            val rCode = CppToolchain.runBinary(outBin, emptyList(), tmpDir, { runOut.append(it) }, { null }, { false })
            assertEquals(0, rCode)
            assertEquals("1 2 7 9 | fact(5)=120 | sqrt(81)=9\n", runOut.toString())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `python3 parser handles functions f-strings and comprehensions`() {
        val code = """
            def fib(n):
                a, b = 0, 1
                res = []
                for _ in range(n):
                    res.append(a)
                    a, b = b, a + b
                return res
            print(f"fib={fib(6)}")
            sq = [x * x for x in range(5) if x % 2 == 0]
        """.trimIndent()
        val tokens = PythonLexer(code).tokenize()
        val stmts = PythonParser(tokens).parseProgram()
        assertEquals(3, stmts.size)
    }

    @Test
    fun `shell parser handles pipes redirections and logical chaining`() {
        val chain = ShellParser.parseChain("cat main.cpp | grep int > out.txt && echo ok")
        assertEquals(2, chain.size)
        assertEquals(2, chain[0].pipeline.commands.size)
        assertEquals("&&", chain[1].connector)
        assertEquals("out.txt", chain[0].pipeline.commands[1].redirections.first().target)
    }
}
