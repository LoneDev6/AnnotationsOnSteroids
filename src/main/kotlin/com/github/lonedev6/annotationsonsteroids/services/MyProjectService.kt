package com.github.lonedev6.annotationsonsteroids.services

import com.github.lonedev6.annotationsonsteroids.MyBundle
import com.intellij.openapi.project.Project

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
