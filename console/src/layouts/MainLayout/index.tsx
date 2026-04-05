import { Layout } from "antd";
import { Routes, Route, useLocation, Navigate } from "react-router-dom";
import Sidebar from "../Sidebar";
import Header from "../Header";
import styles from "../index.module.less";
import Chat from "../../pages/Chat";
import SessionsPage from "../../pages/Control/Sessions";
import AgentConfigPage from "../../pages/Agent/Config";
import SkillsPage from "../../pages/Agent/Skills";
import SkillPoolPage from "../../pages/Agent/SkillPool";
import ToolsPage from "../../pages/Agent/Tools";
import WorkspacePage from "../../pages/Agent/Workspace";
import MCPPage from "../../pages/Agent/MCP";
import ModelsPage from "../../pages/Settings/Models";
import EnvironmentsPage from "../../pages/Settings/Environments";
import SecurityPage from "../../pages/Settings/Security";
import TokenUsagePage from "../../pages/Settings/TokenUsage";
import AgentsPage from "../../pages/Settings/Agents";

const { Content } = Layout;

const pathToKey: Record<string, string> = {
  "/chat": "chat",
  "/sessions": "sessions",
  "/skills": "skills",
  "/skill-pool": "skill-pool",
  "/tools": "tools",
  "/mcp": "mcp",
  "/workspace": "workspace",
  "/agents": "agents",
  "/models": "models",
  "/environments": "environments",
  "/agent-config": "agent-config",
  "/security": "security",
  "/token-usage": "token-usage",
};

export default function MainLayout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const selectedKey = pathToKey[currentPath] || "chat";

  return (
    <Layout className={styles.mainLayout}>
      <Header />
      <Layout>
        <Sidebar selectedKey={selectedKey} />
        <Content className="page-container">
          <div className="page-content">
            <Routes>
              <Route path="/" element={<Navigate to="/chat" replace />} />
              <Route path="/chat/*" element={<Chat />} />
              <Route path="/sessions" element={<SessionsPage />} />
              <Route path="/skills" element={<SkillsPage />} />
              <Route path="/skill-pool" element={<SkillPoolPage />} />
              <Route path="/tools" element={<ToolsPage />} />
              <Route path="/mcp" element={<MCPPage />} />
              <Route path="/workspace" element={<WorkspacePage />} />
              <Route path="/agents" element={<AgentsPage />} />
              <Route path="/models" element={<ModelsPage />} />
              <Route path="/environments" element={<EnvironmentsPage />} />
              <Route path="/agent-config" element={<AgentConfigPage />} />
              <Route path="/security" element={<SecurityPage />} />
              <Route path="/token-usage" element={<TokenUsagePage />} />
            </Routes>
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}
