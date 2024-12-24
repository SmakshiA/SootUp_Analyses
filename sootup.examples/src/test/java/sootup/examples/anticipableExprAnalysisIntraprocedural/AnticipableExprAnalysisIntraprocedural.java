package sootup.examples.anticipableExprAnalysisIntraprocedural;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import sootup.analysis.interprocedural.icfg.AbstractJimpleBasedICFG;
import sootup.core.graph.StmtGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.basic.Local;
import sootup.core.jimple.common.stmt.*;
import sootup.core.model.Body;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.bytecode.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.views.JavaView;

@Tag("Java8")
public class AnticipableExprAnalysisIntraprocedural {

    private static Map<Stmt, Set<String>> performAnticipableExpressionAnalysis(StmtGraph cfg) {
        Map<Stmt, Set<String>> anticipableExpressions = new HashMap<>();
        Map<Stmt, Set<String>> in = new HashMap<>();
        Map<Stmt, Set<String>> out = new HashMap<>();
        List<Stmt> stmts = cfg.getStmts();
        Collections.reverse(stmts);

        for (Stmt stmt : stmts) {
            out.put(stmt, new HashSet<>());
            in.put(stmt, new HashSet<>());
        }

        boolean changed;
        do {
            changed = false;
            for (Stmt stmt : stmts) {
                if (isIgnoredStatement(stmt)) continue;

                Set<String> outOld = new HashSet<>(out.get(stmt));
                Set<String> outNew = new HashSet<>();
                Collection<?> successors = cfg.getAllSuccessors(stmt);

                if (successors != null && !successors.isEmpty()) {
                    Iterator<?> succIter = successors.iterator();
                    if (succIter.hasNext()) {
                        Object firstSucc = succIter.next();
                        if (firstSucc instanceof Stmt) {
                            Set<String> firstSuccIn = in.get(firstSucc);
                            if (firstSuccIn != null) outNew.addAll(firstSuccIn);

                            while (succIter.hasNext()) {
                                Object succ = succIter.next();
                                if (succ instanceof Stmt) {
                                    Set<String> succIn = in.get(succ);
                                    if (succIn != null) outNew.retainAll(succIn);
                                }
                            }
                        }
                    }
                }

                Set<String> inNew = new HashSet<>(outNew);
                if (stmt instanceof JAssignStmt) {
                    inNew.removeIf(equation -> getDef(stmt).stream().anyMatch(var -> equation.contains(var)));
                }
                inNew.addAll(getUse(stmt));

                in.put(stmt, inNew);
                out.put(stmt, outNew);

                if (!outOld.equals(outNew)) changed = true;
            }
        } while (changed);

        for (Stmt stmt : stmts) {
            Set<String> expressions = new HashSet<>(in.getOrDefault(stmt, Collections.emptySet()));
            anticipableExpressions.put(stmt, filterSet(expressions));
        }

        return anticipableExpressions;
    }

    private static Set<String> filterSet(Set<String> originalSet) {
        Set<String> ignoreSet = Collections.singleton(("<java.lang.System: java.io.PrintStream out>"));
        return originalSet.stream()
                .filter(value -> !ignoreSet.contains(value) && !isInteger(value))
                .collect(Collectors.toSet());
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isIgnoredStatement(Stmt stmt) {
        if (stmt instanceof JInvokeStmt) {
            JInvokeStmt invokeStmt = (JInvokeStmt) stmt;
            String methodSignature = String.valueOf(invokeStmt.getInvokeExpr().getMethodSignature());
            return methodSignature.equals("<java.io.PrintStream: void println(java.lang.String)>");
        }
        return false;
    }

    private static Set<String> getDef(Stmt stmt) {
        Set<String> def = new HashSet<>();
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assignStmt = (JAssignStmt) stmt;
            def.add(assignStmt.getLeftOp().toString());
        }
        return def;
    }

    private static Set<String> getUse(Stmt stmt) {
        Set<String> use = new HashSet<>();
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assignStmt = (JAssignStmt) stmt;
            use.add(assignStmt.getRightOp().toString());
        } else if (stmt instanceof JInvokeStmt) {
            JInvokeStmt invokeStmt = (JInvokeStmt) stmt;
            invokeStmt.getInvokeExpr().getArgs().forEach(arg -> use.add(arg.toString()));
        }
        return use;
    }

    @Test
    public void createByteCodeProject() throws IOException {
        Path pathToBinary = Paths.get("src/test/resources/AnticipableExprAnalysisIntraprocedural/binary");
        AnalysisInputLocation inputLocation = PathBasedAnalysisInputLocation.create(pathToBinary, null);

        View view = new JavaView(inputLocation);
        ClassType classType = view.getIdentifierFactory().getClassType("Test1");

        MethodSignature methodSignature =
                view.getIdentifierFactory()
                        .getMethodSignature(
                                classType, "main", "void", Collections.singletonList("java.lang.String[]"));

        assertTrue(view.getClass(classType).isPresent());
        SootClass sootClass = view.getClass(classType).get();
        view.getMethod(methodSignature);

        assertTrue(sootClass.getMethod(methodSignature.getSubSignature()).isPresent());
        SootMethod sootMethod = sootClass.getMethod(methodSignature.getSubSignature()).get();

        AbstractJimpleBasedICFG icfg = new AbstractJimpleBasedICFG() {
            @Override
            public Collection<SootMethod> getCalleesOfCallAt(Stmt stmt) {
                return null;
            }

            @Override
            public Collection<Stmt> getCallersOf(SootMethod sootMethod) {
                return null;
            }
        };

        StmtGraph cfg = icfg.getOrCreateStmtGraph(sootMethod.getBody());
        Map<Stmt, Set<String>> anticipableExpressions = performAnticipableExpressionAnalysis(cfg);

        // Read the Java source file lines
        Path javaSourcePath = Paths.get("src/test/resources/AnticipableExprAnalysisIntraprocedural/source/Test1.java");
        if (!Files.exists(javaSourcePath)) {
            throw new IOException("Java source file not found: " + javaSourcePath.toString());
        }
        List<String> javaSourceLines = Files.readAllLines(javaSourcePath);

        generateDotFile(cfg, anticipableExpressions);

        // Write the results to a CSV file
        writeAnticipableExpressionsToCSV(anticipableExpressions, javaSourceLines);
    }

    private static void generateDotFile(StmtGraph cfg, Map<Stmt, Set<String>> anticipableExpressionsAtEachStmt) throws IOException {
        try (PrintWriter writer = new PrintWriter("src/test/resources/AnticipableExprAnalysisIntraprocedural/dotFile/cfg.dot")) {
            writer.println("digraph CFG {");
            writer.println("node [shape=box];");

            Map<Stmt, String> nodeIds = new HashMap<>();
            int id = 0;

            for (Object stmtObj : cfg.getStmts()) {
                Stmt stmt = (Stmt) stmtObj;
                nodeIds.put(stmt, "Node" + id++);
                String label = stmt.toString().replace("\"", "\\\"").replace("\n", "\\n");
                Set<String> expressions = anticipableExpressionsAtEachStmt.getOrDefault(stmt, Collections.emptySet());
                String formattedExpressions = String.join(", ", expressions);

                // Get the line number
                int lineNumber = stmt.getPositionInfo().getStmtPosition().getFirstLine();

                // Include the line number in the node label
                writer.printf("\"%s\" [label=\"Line %d: %s\\n[%s]\"];%n",
                        nodeIds.get(stmt), lineNumber, label, formattedExpressions);
            }

            for (Object stmtObj : cfg.getStmts()) {
                Stmt stmt = (Stmt) stmtObj;
                String srcId = nodeIds.get(stmt);
                for (Object successorObj : cfg.getAllSuccessors(stmt)) {
                    Stmt successor = (Stmt) successorObj;
                    String destId = nodeIds.get(successor);
                    writer.printf("\"%s\" -> \"%s\";%n", srcId, destId);
                }
            }

            writer.println("}");
        }
        generatePngFromDot(
                "src/test/resources/AnticipableExprAnalysisIntraprocedural/dotFile/cfg.dot",
                "src/test/resources/AnticipableExprAnalysisIntraprocedural/pngFile/cfg.png"
        );
    }

    private static void writeAnticipableExpressionsToCSV(
            Map<Stmt, Set<String>> anticipableExpressions, List<String> javaSourceLines) throws IOException {
        // Map to store line-wise anticipable expressions
        Map<Integer, Set<String>> anticipableExprsPerLine = new HashMap<>();

        for (Map.Entry<Stmt, Set<String>> entry : anticipableExpressions.entrySet()) {
            Stmt stmt = entry.getKey();
            int lineNum = stmt.getPositionInfo().getStmtPosition().getFirstLine();

            // Skip invalid line numbers
            if (lineNum <= 0 || lineNum > javaSourceLines.size()) {
                continue;
            }

            // Deduplicate expressions for the current line
            anticipableExprsPerLine.putIfAbsent(lineNum, new HashSet<>());
            anticipableExprsPerLine.get(lineNum).addAll(entry.getValue());
        }

        // Write the consolidated data to a CSV file
        try (FileWriter writer = new FileWriter("src/test/resources/AnticipableExprAnalysisIntraprocedural/CSV/AnticipableExprAnalysis.csv")) {
            writer.append("Line Number,Statement,Anticipable Expressions\n");
            for (Map.Entry<Integer, Set<String>> entry : anticipableExprsPerLine.entrySet()) {
                int lineNum = entry.getKey();
                String stmtString = javaSourceLines.get(lineNum - 1).trim();
                String anticipableExprsString = String.join(", ", entry.getValue());

                writer.append(String.valueOf(lineNum))
                        .append(',')
                        .append(stmtString)
                        .append(',')
                        .append(anticipableExprsString)
                        .append('\n');
            }
        }
    }

    private static void generatePngFromDot(String dotFileName, String pngFileName) {
        ProcessBuilder pb = new ProcessBuilder("dot", "-Tpng", dotFileName, "-o", pngFileName);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("PNG file generated: " + pngFileName);
            } else {
                System.err.println("Error generating PNG file. Exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error generating PNG file: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

}

