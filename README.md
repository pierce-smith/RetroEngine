# RetroEngine

> **A cross-platform 3D engine / editor suite using Java 25 & LWJGL 3.4**

<img width="1408" height="881" alt="RetroEngine" src="https://github.com/user-attachments/assets/8cc3c47f-e890-45fd-b793-7f17ad4252b7" />


---

## Origin & Motivation

**Java's always been an interesting language (and often misunderstood).**

- **Why Java?**: Minecraft is probably one of the main drivers for modern Java development. Look at it, its the most popular game of all-time and also one of the most modded. Java is an easier language to come into than some of the others like C++, and experienced Minecraft modders also deserve another platform they can use their skills on.
- **The Engine Void**: There's only one other full-fledged engine suite made in Java, and it's experience is quite clunky in my opinion. People are always complaining that Java isn't worth the hassle or the most performant for games. Maybe a few years ago that would've been true, but with JDK 25, we've successfully targeted both Metal 3 for macOS and Vulkan 1.3 for Windows/Linux/macOS (through MoltenVK).
- **The Month-Long Sprint**: As a graduate of computer science pre-LLM era, I was used to finishing software in a timecrunch. I previously had to create a fully functioning game with a small team in 4 weeks and present it to the class. I figured, why not give the timecrunch another try? If it's so hard, let's see how far I could get.

---

## Key Architectural Features

Starting out as a 1-month proof-of-concept, RetroEngine now implements a fully functional core architecture drawing inspiration from classic favorites and modern powerhouses (RenderWare, Gamebryo, Creation Engine):

- **Modern Java 25 Core**: Leverages JDK 25 Virtual Threads for low-overhead async asset loading and parallel execution.
- **Dual Graphics RHI (Vulkan 1.3 & Metal 3)**: Low-level rendering hardware abstraction layer supporting cross-platform Vulkan on Windows/Linux/macOS and native Metal 3 on macOS.
- **Low-Allocation Simulation**: Entities and components built to keep garbage collection pauses off the frame loop. The simulation is measured allocation-free per frame in the test suite. The renderer isn't measured yet.
- **Live Java Scripting**: In-memory javac compilation for gameplay script hot reloading, with the full modern Java language.
- **Dockable Visual Editor**: Integrated workspace hosting 3D scene viewports, spatial transform gizmos, asset browsers, and inspector panels.
- **Mesh Decimator**: Implements a mesh decimator that targets high vertex meshes and obliterates them down to a target of ~4,500 vertices. This has been a bigger pain of the features to implement and expect bugs on meshes made of a bunch of other submodules.
- **Modding Suite**: A modding runtime for all exported games. Mods are trusted code: they run with the same access to the player's machine as the game itself, so players should only install mods they trust.

---

## Project Status & Known Limitations

> **Developer Note**: RetroEngine was originally built during an intensive 1-month crunch as an architectural proof-of-concept and "software sprint." Expect bugs, unoptimized code paths, and areas ready for community refinement!

---

## Getting Started

### Prerequisites

- **JDK 25** (or newer)
- Gradle isn't needed separately: use the included wrapper (`./gradlew`, Gradle 9.6)
- GPU supporting **Vulkan 1.3** (Windows/Linux) or **Metal 3** (macOS 13+)

### Quickstart

- Download your platform's version from the Releases section if on Windows or Apple Silicon. If on Linux or an Intel Mac, you will need to compile from source. Linux support is experimental for now: CI runs the tests there, but it hasn't been tested on real hardware.
- To get the most updated version, compiling from source is always recommended. Versions in the Releases section will be updated after a certain amount of commits to the source code.

### Running the Tests

`./gradlew test` runs the automated suite. It needs no GPU or window. See Volume 5 §3.0 for how it works.

---

## Contributing & Pull Requests

**This was a test of my will and eagerness to get back into the workflow. I welcome feedback and public contributions to the software.**

- All public contributions and Pull Requests should target the beta-test staging branch.
- Standard branch protections are enabled on main to ensure code quality and stability.

---

## License & Credits

- **License**: Distributed under the **Apache License 2.0**. See LICENSE.txt for details.
- **Sample Assets**: Big thanks to Kenney for the UI audio sounds, Drillimpact for the rigged arms, GGBotNet for the car assets, and MintoDog for the Infinite Driver soundtrack.
- **Acknowledgments**: *Special thanks to all team members, friends, and early testers who provided feedback and support during this 1-month sprint.*
