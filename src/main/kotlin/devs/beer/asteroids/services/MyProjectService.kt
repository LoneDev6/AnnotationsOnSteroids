package devs.beer.asteroids.services

import com.intellij.openapi.project.Project
import devs.beer.asteroids.AnnotationsOnSteroidsBundle

class MyProjectService(project: Project) {

    init {
        println(AnnotationsOnSteroidsBundle.message("projectService", project.name))
    }
}
