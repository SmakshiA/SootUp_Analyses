package sootup.examples.liveVariableAnalysisInterprocedural;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import sootup.analysis.interprocedural.icfg.JimpleBasedInterproceduralCFG;
import sootup.core.graph.StmtGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.basic.Value;
import sootup.core.jimple.common.ref.JInstanceFieldRef;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.java.bytecode.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import sootup.core.views.View;

public class LiveVariableAnalysisInterprocedural {

    private static Map<SootMethod, Map<Stmt, Set<String>>> methodLiveVars = new HashMap<>();
    private static Map<SootMethod, Map<Stmt, Set<String>>> resultsMap = new HashMap<>();

    private static Map<Stmt, Set<String>> performLiveVariableAnalysis(StmtGraph cfg, JimpleBasedInterproceduralCFG icfg, SootMethod currentMethod) {
        if (resultsMap.containsKey(currentMethod)) {
            return resultsMap.get(currentMethod);
        }

        Map<Stmt, Set<String>> in = new HashMap<>();
        Map<Stmt, Set<String>> out = new HashMap<>();
        List<Stmt> stmts = cfg.getStmts();
        Collections.reverse(stmts);

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
                        Map<Stmt, Set<String>> calleeLiveVars = resultsMap.getOrDefault(callee, performLiveVariableAnalysis(calleeCfg, icfg, callee));
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

        resultsMap.put(currentMethod, in);
        methodLiveVars.put(currentMethod, in);

        return in;
    }

    private static Set<String> getDef(Stmt stmt) {
        Set<String> def = new HashSet<>();
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assignStmt = (JAssignStmt) stmt;
            if (assignStmt.getLeftOp() instanceof JInstanceFieldRef) {
                JInstanceFieldRef instanceFieldRef = (JInstanceFieldRef) assignStmt.getLeftOp();
                String fieldName = instanceFieldRef.getFieldSignature().getName();
                def.add(fieldName);  // Captures "this" with field name
            } else if (assignStmt.getLeftOp() instanceof Local) {
                def.add(assignStmt.getLeftOp().toString());
            }
        }
        return def;
    }

    private static Set<String> getUse(Stmt stmt) {
        Set<String> use = new HashSet<>();
        for (Value value : stmt.getUses().collect(Collectors.toList())) {
            if (value instanceof JInstanceFieldRef) {
                JInstanceFieldRef instanceFieldRef = (JInstanceFieldRef) value;
                String fieldName = instanceFieldRef.getFieldSignature().getName();
                use.add("this." + fieldName);  // Captures "this" with field name
            } else if (value instanceof Local) {
                use.add(value.toString());
            }
        }
        return use;
    }

    @Test
    public void testInterproceduralLiveVariableAnalysis() throws IOException {
        Path pathToBinary = Paths.get("src/test/resources/LiveVariableAnalysisInterprocedural/binary");
        AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);
        View view = new JavaView(inputLocation);
        ClassType classType = view.getIdentifierFactory().getClassType("Test1");

        MethodSignature methodSignature = view.getIdentifierFactory()
                .getMethodSignature(classType, "s", "void", Arrays.asList());

        SootClass sootClass = view.getClass(classType).get();
        SootMethod sootMethod = sootClass.getMethod(methodSignature.getSubSignature()).get();

        JimpleBasedInterproceduralCFG icfg = new JimpleBasedInterproceduralCFG(view, methodSignature, false, false);
        StmtGraph cfg = icfg.getOrCreateStmtGraph(sootMethod.getBody());

        System.out.println();
        System.out.println(cfg);

        Map<Stmt, Set<String>> liveVarsAtEachStmt = performLiveVariableAnalysis(cfg, icfg, sootMethod);

        Path javaSourcePath = Paths.get("src/test/resources/LiveVariableAnalysisInterprocedural/source/Test1.java");

        if (!Files.exists(javaSourcePath)) {
            throw new IOException("Java source file not found: " + javaSourcePath.toString());
        }

        List<String> javaSourceLines = Files.readAllLines(javaSourcePath);

        writeLiveVariableAnalysisToCSV(liveVarsAtEachStmt, mappingLocalsToVariableNames(sootMethod.getBody()), javaSourceLines);

        Map<String,String> localMapping = mappingLocalsToVariableNames(sootMethod.getBody());
        generateCFGDotFile(cfg, liveVarsAtEachStmt, "src/test/resources/LiveVariableAnalysisInterprocedural/dotFile/cfg.dot",localMapping, javaSourceLines);
        generatePNGFromDotFile("src/test/resources/LiveVariableAnalysisInterprocedural/dotFile/cfg.dot", "src/test/resources/LiveVariableAnalysisInterprocedural/pngFile/cfg.png");
    }

    private static void writeLiveVariableAnalysisToCSV(Map<Stmt, Set<String>> liveVarsAtEachStmt,
                                                       Map<String, String> localVarMapping,
                                                       List<String> javaSourceLines) throws IOException {
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
                String actualVarName = var.startsWith("this.") ? var.substring(5) : var;
                if (actualVarName.equals("this")) {
                    continue; // Skip "this"
                }
                if (localVarMapping.containsKey(actualVarName)) {
                    liveVars.add(localVarMapping.get(actualVarName));
                } else {
                    liveVars.add(actualVarName);  // For fields accessed through "this"
                }
            }

            String liveVarsString = String.join(", ", liveVars);
            finalLiveVarsPerLine.put(lineNum, stmtString + "," + liveVarsString);
        }

        try (FileWriter writer = new FileWriter("src/test/resources/LiveVariableAnalysisInterprocedural/CSV/LiveVariableAnalysis.csv")) {
            writer.append("Line Number,Statement,Live Variables\n");
            for (Map.Entry<Integer, String> entry : finalLiveVarsPerLine.entrySet()) {
                writer.append(String.valueOf(entry.getKey())).append(",").append(entry.getValue()).append("\n");
            }
        }
    }

    private static Map<String, String> mappingLocalsToVariableNames(Body body) {
        Map<String, String> localVarMapping = new HashMap<>();
        for (Local local : body.getLocals()) {
            localVarMapping.put(local.toString(), local.getName());
        }
        return localVarMapping;
    }

    private static void generateCFGDotFile(StmtGraph cfg, Map<Stmt, Set<String>> liveVars,
                                           String outputDotFile, Map<String, String> localVarMapping,
                                           List<String> javaSourceLines) throws IOException {
        StringBuilder dotBuilder = new StringBuilder();
        dotBuilder.append("digraph CFG {\n");
        dotBuilder.append("    node [shape=rectangle];\n"); // Default node shape for statements

        // Create nodes for statements
        for (Object stmtObj : cfg.getStmts()) {
            Stmt stmt = (Stmt) stmtObj;
            String stmtLabel = stmt.toString().replace("\"", "\\\""); // Escape quotes for DOT format

            // Get the source code line number
            int lineNum = stmt.getPositionInfo().getStmtPosition().getFirstLine();
            String sourceCodeLine = (lineNum > 0 && lineNum <= javaSourceLines.size())
                    ? javaSourceLines.get(lineNum - 1).trim()
                    : "Unknown line";

            // Map live variables to their corresponding names, filtering out $stack variables
            Set<String> variables = liveVars.getOrDefault(stmt, Collections.emptySet());
            Set<String> filteredVars = new HashSet<>();
            for (String var : variables) {
                if (var.startsWith("this.")) {
                    filteredVars.add(var.substring(5)); // Remove "this." prefix for field names
                } else if (!var.equals("this") && !var.startsWith("$stack") && localVarMapping.containsKey(var)) {
                    filteredVars.add(localVarMapping.get(var)); // Map local variables to their names
                } else if (!var.equals("this") && !var.startsWith("$stack")) {
                    filteredVars.add(var); // Use variable as is if no mapping is found
                }
            }

            // Combine the statement, its live variables, and source code line into a single node label
            String liveVarsLabel = String.format("Live variables: [%s]", String.join(", ", filteredVars));
            String combinedLabel = String.format("Line %d: %s\\n%s\\n%s",
                    lineNum, sourceCodeLine, stmtLabel, liveVarsLabel);

            dotBuilder.append(String.format("    \"%s\" [label=\"%s\"];\n", stmtLabel, combinedLabel));
        }

        // Add edges between the statement nodes (for control flow)
        for (Object stmtObj : cfg.getStmts()) {
            Stmt stmt = (Stmt) stmtObj;
            for (Object succObj : cfg.getAllSuccessors(stmt)) {
                Stmt successorStmt = (Stmt) succObj;
                dotBuilder.append(String.format("    \"%s\" -> \"%s\";\n", stmt.toString(), successorStmt.toString()));
            }
        }

        dotBuilder.append("}\n");
        Files.write(Paths.get(outputDotFile), dotBuilder.toString().getBytes());
    }

    private static void generatePNGFromDotFile(String inputDotFile, String outputPngFile) throws IOException {
        try {
            String cmd = "dot -Tpng " + inputDotFile + " -o " + outputPngFile;
            Process process = Runtime.getRuntime().exec(cmd);
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } catch (IOException e) {
            System.err.println("Graphviz's 'dot' command is not available on your system. Ensure Graphviz is installed and in your PATH.");
            e.printStackTrace();
        }
    }
}