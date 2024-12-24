package sootup.examples.liveVariableAnalysisIntraprocedural;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.Test;
import sootup.analysis.interprocedural.icfg.AbstractJimpleBasedICFG;
import sootup.core.graph.StmtGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.bytecode.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import sootup.core.views.View;


public class LiveVariableAnalysisIntraprocedural {

    private static Map<SootMethod, Map<Stmt, Set<String>>> methodLiveVars = new HashMap<>();

    private static Map<Stmt, Set<String>> performLiveVariableAnalysis(StmtGraph cfg, AbstractJimpleBasedICFG icfg, SootMethod currentMethod) {
        Map<Stmt, Set<String>> in = new HashMap<>();
        Map<Stmt, Set<String>> out = new HashMap<>();
        List<Stmt> stmts = cfg.getStmts();

        for (Stmt stmt : stmts) {
            in.put(stmt, new HashSet<>());
            out.put(stmt, new HashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            for (Stmt stmt : stmts) {
                Set<String> inOld = new HashSet<>(in.get(stmt));
                Set<String> outOld = new HashSet<>(out.get(stmt));

                Set<String> inNew = new HashSet<>(out.get(stmt));
                inNew.removeAll(getDef(stmt));
                inNew.addAll(getUse(stmt));

                Set<String> outNew = new HashSet<>();
                for (Object succ : cfg.getAllSuccessors(stmt)) {
                    outNew.addAll(in.get(succ));
                }

                if (icfg.isCallStmt(stmt)) {
                    for (SootMethod callee : icfg.getCalleesOfCallAt(stmt)) {
                        StmtGraph calleeCfg = icfg.getOrCreateStmtGraph(callee.getBody());
                        Map<Stmt, Set<String>> calleeLiveVars = methodLiveVars.getOrDefault(callee, performLiveVariableAnalysis(calleeCfg, icfg, callee));
                        Stmt calleeEntryStmt = calleeCfg.getStartingStmt();
                        outNew.addAll(calleeLiveVars.get(calleeEntryStmt));
                    }
                }

                out.put(stmt, outNew);
                in.put(stmt, inNew);

                if (!inOld.equals(inNew) || !outOld.equals(outNew)) {
                    changed = true;
                }
            }
        } while (changed);

        methodLiveVars.put(currentMethod, in);
        return in;
    }

    private static Set<String> getDef(Stmt stmt) {
        Set<String> def = new HashSet<>();
        if (stmt.getDef().isPresent()) {
            def.add(stmt.getDef().get().toString());
        }
        return def;
    }

    private static Set<String> getUse(Stmt stmt) {
        Set<String> use = new HashSet<>();
        stmt.getUses().forEach(value -> use.add(value.toString()));
        return use;
    }

    private static Map<String, String> mapLocalsToVariableNames(Body body) {
        Map<String, String> localVarMapping = new HashMap<>();
        for (Local local : body.getLocals()) {
            localVarMapping.put(local.getName(), local.getName());
        }
        return localVarMapping;
    }

    private static void generateCFGDotFile(StmtGraph cfg, Map<Stmt, Set<String>> liveVars, String outputDotFile) throws IOException {
        StringBuilder dotBuilder = new StringBuilder();
        dotBuilder.append("digraph CFG {\n");
        dotBuilder.append("    node [shape=rectangle];\n");

        // Iterate through the statements to generate nodes
        for (Object stmtObj : cfg.getStmts()) {
            if (stmtObj instanceof Stmt) {
                Stmt stmt = (Stmt) stmtObj;

                // Skip stack variables
                if (containsStackVariable(stmt)) {
                    continue;
                }

                String stmtLabel = stmt.toString().replace("\"", "\\\"");
                int lineNumber = stmt.getPositionInfo().getStmtPosition().getFirstLine();

                if (stmtLabel.startsWith("$stack")) {
                    continue;
                }

                Set<String> variables = liveVars.getOrDefault(stmt, Collections.emptySet());
                Set<String> filteredVars = new HashSet<>();

                // Exclude stack variables (those with a $ in their names)
                for (String var : variables) {
                    if (isVariable(var) && !var.contains("$")) {
                        filteredVars.add(var);
                    }
                }

                String liveVarsLabel = String.format(
                        "Line: %d \\n Live vars: [%s]\\n%s",
                        lineNumber,
                        String.join(", ", filteredVars),
                        stmtLabel
                );

                dotBuilder.append(String.format("    \"%s\" [label=\"%s\"];\n", stmtLabel, liveVarsLabel));
            }
        }

        // Generate edges representing the control flow graph
        for (Object stmtObj : cfg.getStmts()) {
            if (!(stmtObj instanceof Stmt)) continue;

            Stmt stmt = (Stmt) stmtObj;
            String stmtLabel = stmt.toString().replace("\"", "\\\"");

            if (stmtLabel.startsWith("$stack")) {
                continue; // Skip this node completely
            }

            for (Object succObj : cfg.getAllSuccessors(stmt)) {
                if (!(succObj instanceof Stmt)) continue;

                Stmt succ = (Stmt) succObj;
                String succLabel = succ.toString().replace("\"", "\\\"");

                if (succLabel.startsWith("$stack")) {
                    // Skip the successor node and connect the current node to its successor's successor
                    for (Object nextSuccObj : cfg.getAllSuccessors(succ)) {
                        if (nextSuccObj instanceof Stmt) {
                            Stmt nextSucc = (Stmt) nextSuccObj;
                            String nextSuccLabel = nextSucc.toString().replace("\"", "\\\"");
                            dotBuilder.append(String.format("    \"%s\" -> \"%s\";\n", stmtLabel, nextSuccLabel));
                        }
                    }
                } else {
                    dotBuilder.append(String.format("    \"%s\" -> \"%s\";\n", stmtLabel, succLabel));
                }
            }
        }
        dotBuilder.append("}\n");

        // Write the DOT file to disk
        Files.write(Paths.get(outputDotFile), dotBuilder.toString().getBytes());
    }

    private static boolean containsStackVariable(Stmt stmt) {
        if (stmt.getDef().isPresent()) {
            String defVar = stmt.getDef().get().toString();
            return defVar.contains("$");  // Skip stack variables
        }
        return false;
    }

    private static boolean isVariable(String str) {
        return str.matches("[a-zA-Z_$][a-zA-Z\\d_$]*");
    }

    private static void generatePngUsingDot(String dotFilePath, String outputPngFilePath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("dot", "-Tpng", dotFilePath, "-o", outputPngFilePath);
        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode == 0) {
            System.out.println("PNG file generated at: " + outputPngFilePath);
        } else {
            System.err.println("Error generating PNG. Exit code: " + exitCode);
        }
    }

    @Test
    public void testInterproceduralLiveVariableAnalysis() throws IOException, InterruptedException {
        Path pathToBinary = Paths.get("src/test/resources/LiveVariableAnalysisIntraprocedural/binary");
        AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);
        View view = new JavaView(inputLocation);
        ClassType classType = view.getIdentifierFactory().getClassType("Test1");
        MethodSignature methodSignature = view.getIdentifierFactory()
                .getMethodSignature(classType, "main", "void", Collections.singletonList("java.lang.String[]"));

        SootClass sootClass = view.getClass(classType).get();
        SootMethod sootMethod = sootClass.getMethod(methodSignature.getSubSignature()).get();

        AbstractJimpleBasedICFG icfg = new AbstractJimpleBasedICFG() {
            @Override
            public Collection<SootMethod> getCalleesOfCallAt(Stmt stmt) {
                return Collections.emptyList();
            }

            @Override
            public Collection<Stmt> getCallersOf(SootMethod sootMethod) {
                return Collections.emptyList();
            }
        };

        StmtGraph cfg = icfg.getOrCreateStmtGraph(sootMethod.getBody());
        Map<String, String> mapping = mapLocalsToVariableNames(sootMethod.getBody());
        Map<Stmt, Set<String>> liveVarsAtEachStmt = performLiveVariableAnalysis(cfg, icfg, sootMethod);

        generateCFGDotFile(cfg, liveVarsAtEachStmt, "src/test/resources/LiveVariableAnalysisIntraprocedural/dotFile/cfg.dot");
        System.out.println("DOT file generated: cfg.dot");

        generatePngUsingDot("src/test/resources/LiveVariableAnalysisIntraprocedural/dotFile/cfg.dot", "src/test/resources/LiveVariableAnalysisIntraprocedural/pngFile/cfg.png");

        Path javaSourcePath = Paths.get("src/test/resources/LiveVariableAnalysisIntraprocedural/source/Test1.java");
        if (!Files.exists(javaSourcePath)) {
            throw new IOException("Java source file not found: " + javaSourcePath.toString());
        }
        List<String> javaSourceLines = Files.readAllLines(javaSourcePath);

        writeLiveVariableAnalysisToCSV(liveVarsAtEachStmt, mapping, javaSourceLines);
    }

    private static void writeLiveVariableAnalysisToCSV(Map<Stmt, Set<String>> liveVarsAtEachStmt, Map<String, String> localVarMapping, List<String> javaSourceLines) throws IOException {
        Map<Integer, String> finalLiveVarsPerLine = new HashMap<>();

        for (Map.Entry<Stmt, Set<String>> entry : liveVarsAtEachStmt.entrySet()) {
            Stmt stmt = entry.getKey();
            int lineNum = stmt.getPositionInfo().getStmtPosition().getFirstLine();
            if (lineNum <= 0 || lineNum > javaSourceLines.size()) {
                continue;
            }
            String stmtString = javaSourceLines.get(lineNum - 1).trim();
            Set<String> liveVars = new HashSet<>();
            for (String var : entry.getValue()) {
                if (localVarMapping.containsKey(var) && !var.startsWith("$")) {
                    liveVars.add(localVarMapping.get(var));
                }
            }

            String liveVarsString = String.join(", ", liveVars);
            if (!liveVarsString.isEmpty()) {
                finalLiveVarsPerLine.put(lineNum, stmtString + "->" + liveVarsString);
            }
            else{
                finalLiveVarsPerLine.put(lineNum, stmtString );
            }
        }

        try (FileWriter writer = new FileWriter("src/test/resources/LiveVariableAnalysisIntraprocedural/CSV/LiveVariableAnalysis.csv")) {
            writer.append("Line Number,Statement,Live Variables\n");
            for (Map.Entry<Integer, String> entry : finalLiveVarsPerLine.entrySet()) {
                int lineNum = entry.getKey();
                String content = entry.getValue();
                writer.append(String.valueOf(lineNum)).append(',').append(content).append('\n');
            }
        }
    }
}
