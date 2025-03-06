package com.netgrif.maven.plugin.module.assembly;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class AssemblyDescriptorBuilder {

    private final Document xml;
    private final Element root;
    private final Element formats;

    private String id;

    public AssemblyDescriptorBuilder() {
        xml = DocumentHelper.createDocument();
        root = xml.addElement("assembly");
        root.addAttribute("xmlns", "http://maven.apache.org/ASSEMBLY/2.2.0");
        root.addAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        root.addAttribute("xsi:schemaLocation", "http://maven.apache.org/ASSEMBLY/2.2.0 https://maven.apache.org/xsd/assembly-2.2.0.xsd");
        this.formats = root.addElement("formats");
    }

    public AssemblyDescriptorBuilder id(String id) {
        this.id = id;
        root.addElement("id").setText(id);
        return this;
    }

    public AssemblyDescriptorBuilder format(String format) {
        formats.addElement("format").setText(format);
        return this;
    }

    public AssemblyDescriptorBuilder includeBaseDirectory(Boolean value) {
        root.addElement("includeBaseDirectory").setText(value.toString());
        return this;
    }

    public FileSetsBuilder fileSets() {
        Element fileSets = root.addElement("fileSets");
        return new FileSetsBuilder(this, fileSets);
    }

    public DependencySetsBuilder dependencySets() {
        Element sets = root.addElement("dependencySets");
        return new DependencySetsBuilder(this, sets);
    }

    public File build(String dir) throws IOException {
        FileWriter out = new FileWriter(dir + File.separator + id + ".xml");
        xml.write(out);
        out.close();
        return new File(dir + File.separator + id + ".xml");
    }

}
