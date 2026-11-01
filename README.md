# RetroEngine

> **A cross-platform 3D engine / editor suite using Java 25 & LWJGL 3.4**

<img width="1408" height="881" alt="Screenshot 2026-11-01 at 14 29 13" src="https://github.com/user-attachments/assets/9bd2baa2-da32-40a0-bb66-52d984f9a89f" />

---

## Origin & Motivation

**Java's always been an interesting language (and often misunderstood).**

- **Why Java?**: Minecraft is probably the one of the main drivers for modern Java development. Look at it, its the most popular game of all-time and also one of the most modded. Java is an easier language to come into than some of the others like C++, and experienced Minecraft modders also deserver another platform they can use their skills on.
- **The Engine Void**: There's only one other full-fledged engine suite made in Java, and it's experience is quite clunky in my opinion. People are always complaining that Java isn't worth the hassle or the most performant for games. Maybe a few years ago that would've been true, but with JDK 25, we've successfully targeted both Metal 3 for macOS and Vulkan 1.3 for Windows/Linux/macOS (through MoltenVK).
- **The 30-Day Sprint**: As a graduate of computer science pre-LLM era, I was used to finishing software in a timecrunch. I previously had to create a fully functioning game with a small team in 4 weeks and present it to the class. I figured, why not give the timecrunch another try? If it's so hard, let's see how far I could get. Besides a few oversights, and not getting the mod SDK built into the architecture, RetroEngine is complete enough for me to consider a beta.

---

## Key Architectural Features

Even as a 1-month proof-of-concept, RetroEngine implements a functional core architecture drawing inspiration from classic favorites and modern powerhouses (RenderWare, Gamebryo, Creation Engine):

- **Modern Java 25 Core**: Leverages JDK 25 Virtual Threads for low-overhead async asset loading and parallel execution.
- **Dual Graphics RHI (Vulkan 1.3 & Metal 3)**: Low-level rendering hardware abstraction layer supporting cross-platform Vulkan on Windows/Linux/macOS and native Metal 3 on macOS.
- **Zero-Allocation ECS Kernel**: Cache-friendly Entity Component System engineered to keep garbage collection pauses off the frame loop.
- **Janino Live Scripting**: Instant in-memory Java compilation for runtime gameplay script hot-reloading.
- **Dockable Visual Editor**: Integrated workspace hosting 3D scene viewports, spatial transform gizmos, asset browsers, and inspector panels.
- **Mesh Decimator**: Implements a mesh decimator that targets high vertex meshes and obliterates them down to a target of ~4,500 vertices. This has been a bigger pain of the features to implement and expect bugs on meshes made of a bunch of other submodules.

---

## Project Status & Known Limitations

> **Developer Note**: RetroEngine was built during an intensive 1-month crunch as an architectural proof-of-concept and "software sprint." Expect bugs, unoptimized code paths, and areas ready for community refinement!

- **What Is Implemented**: Core engine loop, dual RHI rendering pipeline (attempting to capture that sweet sweet RenderWare nostalgia), ECS component system, Janino script engine, 60 Hz physics loop, and the dockable editor suite (including a barebones animator suite).
- **What Is Not Implemented**: The non-destructive modding suite and virtual file system (VFS) stacking were design specs and are **not** included in this 1-month initial beta code.

---

## Getting Started

### Prerequisites

- **JDK 25** (or newer)
- **Gradle 8.x+**
- GPU supporting **Vulkan 1.3** (Windows/Linux) or **Metal 3** (macOS 13+)

### Quickstart

- To test the beta, you will be building the application **from source**. You will need a Java IDE (IntelliJ IDEA, Eclipse, Netbeans).
- Load the project's root folder (RetroEngine) in the IDE of your choice, navigate to src/main/java/com/retro/engine and open the Main.java file.
- In IDEA you'll now press the green play button to build the application.
- **It's possible it may crash on the first build** (especially if you're on Windows). This is due to certain shaders being compiled on my MacBook, and also recent file removal from the repository as they were found to not be CC0. Just run the build again.
- You can use the RetroEngine root folder as a project and open it (it may prompt it has not been initialized as a project, you can without harming anything). There's a few basic script's and assets here as well as additional starter content with new project creation.
- There are a few volumes of pdf documentation in the docs/ folder. Though not all information may be accurate, as the architecture and design scope changed drastically within the development timeframe till now.

<img width="1408" height="881" alt="Screenshot 2026-11-01 at 14 30 00" src="https://github.com/user-attachments/assets/96cdf85e-7c8d-4134-a33d-87bdd02e4bb5" />

---

## Contributing & Pull Requests

**This was a test of my will and eagerness to get back into the workflow. I welcome feedback and public contributions to the software.**

- All public contributions and Pull Requests should target the beta-test staging branch.
- Standard branch protections are enabled on main to ensure code quality and stability.

---

## License & Credits

- **License**: Distributed under the **Apache License 2.0**. See LICENSE.txt for details.
- **Sample Assets**: Big thanks to Kenney for the UI audio sounds, and Drillimpact for the rigged arms.
- **Acknowledgments**: *Special thanks to all team members, friends, and early testers who provided feedback and support during this 1-month sprint.*
