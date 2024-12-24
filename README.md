<h1>Installation Guide</h1>
<p>
In our repository, we have only included the sootup.examples folder and other necessary resources. To run it, first install SootUp, and then replace the existing sootup.examples folder with this new one.
</p>

<h2>Prerequisites</h2>
<p>
Java Environment: Install Java JDK 8u421.<br>
Graphviz: Required for visualizing graphs.
</p>

<h2>SootUp Installation</h2>
<ol>
  <li>
    <strong>Install Java:</strong> Download and install JDK 8u421.
  </li>
  <li>
    <strong>Set environment variables:</strong> Add <code>C:\Program Files\Java\jdk\bin</code> to the <code>Path</code> and create <code>JAVA_HOME</code> with the JDK folder path.
  </li>
  <li>
    <strong>Download SootUp:</strong> Either download the ZIP from the SootUp GitHub and extract or clone via Git using the command: 
    <br>
    <code>git clone https://github.com/secure-software-engineering/SootUp.git</code>
  </li>
  <li>
    <strong>Use IntelliJ IDEA or Eclipse:</strong> Select JDK 8u421 and load Maven scripts when prompted.
  </li>
</ol>

<h2>Graphviz Installation</h2>
<ol>
  <li>
    <strong>Download:</strong> Get the installer from the Graphviz Download Page.
  </li>
  <li>
    <strong>Install:</strong> Run the installer and complete the setup.
  </li>
  <li>
    <strong>Setup:</strong> Add the <code>bin</code> folder (e.g., <code>C:\Program Files\Graphviz\bin</code>) to the system <code>Path</code>.
  </li>
  <li>
    <strong>Verify:</strong> Run <code>dot -version</code> in the terminal to confirm the installation.
  </li>
</ol>

<h2>Instructions for Trying Out Different Test Files</h2>
<ol>
  <li>
    <strong>Create a Test File:</strong> Create a Java source file (e.g., <code>TestFile.java</code>) in your desired location.
  </li>
  <li>
    <strong>Compile the Source File:</strong>
    <ul>
      <li>
        Open a terminal and navigate to the directory containing your source:
        <br>
        <code>cd "path/to/source/directory/of/the/respective/analysis/in/the/resources"</code>
      </li>
      <li>
        Compile the file using:
        <br>
        <code>javac -g TestFile.java</code>
        <br>
        This generates a <code>.class</code> file in the same directory.
      </li>
    </ul>
  </li>
  <li>
    <strong>Move the Compiled File:</strong> Move the generated <code>.class</code> file into the binary directory:
    <br>
    <code>mv TestFile.class "path/to/binary/directory/of/the/respective/analysis/in/the/resources"</code>
  </li>
  <li>
    <strong>Update the Code:</strong>
    <ul>
      <li>
        Update the <code>classType</code> to match the name of your Java class (e.g., <code>TestFile</code>).
      </li>
      <li>
        Update the <code>methodSignature</code> based on your test file's methods:
        <ul>
          <li>
            For a parameterless method named <code>myMethod</code>:
            <br>
            <code>
            MethodSignature methodSignature = view.getIdentifierFactory()
            .getMethodSignature(classType, "myMethod", "void", Collections.emptyList());
            </code>
          </li>
          <li>
            For a parameterized method named <code>myMethod</code> (e.g., <code>public void myMethod(int param1, String param2)</code>):
            <br>
            <code>
            MethodSignature methodSignature = view.getIdentifierFactory()
            .getMethodSignature(classType, "myMethod", "void", Arrays.asList("int", "java.lang.String"));
            </code>
          </li>
        </ul>
      </li>
    </ul>
  </li>
</ol>
